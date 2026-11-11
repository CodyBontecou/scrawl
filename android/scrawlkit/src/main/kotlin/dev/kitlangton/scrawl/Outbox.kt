package dev.kitlangton.scrawl

import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Durable delivery for recordings sent to an HTTPS endpoint (a hosted Problems board rather than a
 * Mac on the LAN).
 *
 * Each recording is written to disk as its framed ingest payload (4-byte big-endian timeline length,
 * timeline JSON, MP4) plus a small metadata file before anything is sent, and only deleted after a
 * 2xx. Uploads stream the file, so they never hold the movie in memory, and [OutboxWorker] (WorkManager)
 * kicks the outbox when the app was killed with entries pending, standing in for iOS's background
 * `URLSession`.
 *
 * - 401 and 403 (the link was revoked), 413 (too large) and every other 4xx except 408 and 429 are
 *   permanent: the entry stays on disk, marked failed, until the link is renewed or it's shared.
 * - Everything else (no network, timeouts, 408, 429, 5xx) is retried with backoff, and whenever
 *   `kick(force = true)` is called (network back, app foregrounded, launch).
 * - An entry is never uploading twice at once: in-flight uploads are tracked by entry ID.
 *
 * The server dedupes on person and `recordedAt`, so sending an entry again after a lost 2xx is safe.
 * All state lives on [queue].
 */
class Outbox(
    val directory: File,
    private val uploader: Uploader = HttpUploader(),
    val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val onEvent: (Event) -> Unit,
) {
    enum class Failure(val raw: String) {
        /** 401/403: the Scrawl link was revoked or is wrong. */
        Unauthorized("unauthorized"),

        /** 413, or the payload is over the size limit before sending. */
        TooLarge("tooLarge"),

        /** Any other 4xx except 408/429 (malformed frame or timeline, missing Content-Length, …). */
        Rejected("rejected");

        companion object {
            fun of(raw: String?): Failure? = entries.firstOrNull { it.raw == raw }
        }
    }

    data class Entry(
        val id: String,
        val target: String,
        val recordedAt: String,
        val createdAt: Long,
        val payloadBytes: Long,
        val attempts: Int = 0,
        val lastError: String? = null,
        val failure: Failure? = null,
        val nextAttemptAt: Long? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("target", target)
            put("recordedAt", recordedAt)
            put("createdAt", createdAt)
            put("payloadBytes", payloadBytes)
            put("attempts", attempts)
            lastError?.let { put("lastError", it) }
            failure?.let { put("failure", it.raw) }
            nextAttemptAt?.let { put("nextAttemptAt", it) }
        }

        companion object {
            fun fromJson(json: JSONObject): Entry = Entry(
                id = json.getString("id"),
                target = json.getString("target"),
                recordedAt = json.getString("recordedAt"),
                createdAt = json.getLong("createdAt"),
                payloadBytes = json.getLong("payloadBytes"),
                attempts = json.optInt("attempts", 0),
                lastError = json.optString("lastError").takeIf { json.has("lastError") },
                failure = Failure.of(json.optString("failure").takeIf { json.has("failure") }),
                nextAttemptAt = if (json.has("nextAttemptAt")) json.getLong("nextAttemptAt") else null,
            )
        }
    }

    sealed class Event {
        abstract val id: String

        /** An upload attempt began. */
        data class Started(override val id: String) : Event()

        /** Bytes went out: [fraction] of the payload so far. */
        data class Progress(override val id: String, val fraction: Double) : Event()

        /** The server took it (a 2xx), and said what became of it. */
        data class Sent(override val id: String, val reply: IngestReply) : Event()

        /** A transient failure; the entry is tried again at [next] (or sooner on a kick). */
        data class Retrying(override val id: String, val error: String, val next: Long) : Event()
        data class Failed(override val id: String, val failure: Failure) : Event()
    }

    sealed class Outcome {
        data object Success : Outcome()
        data class Permanent(val failure: Failure) : Outcome()
        data object Retry : Outcome()
    }

    /** Sends one framed payload; returns the HTTP status (null when no response arrived) and body. */
    fun interface Uploader {
        fun upload(
            url: URL,
            token: String,
            payload: File,
            length: Long,
            onProgress: (sent: Long) -> Unit,
        ): Pair<Int?, ByteArray>

        /** Aborts every upload in progress (the device was unpaired: URLSession's invalidateAndCancel). */
        fun cancelAll() = Unit
    }

    private val queue: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dev.kitlangton.scrawl.outbox").apply { isDaemon = true }
    }
    private val uploads = Executors.newCachedThreadPool { r ->
        Thread(r, "dev.kitlangton.scrawl.outbox.upload").apply { isDaemon = true }
    }
    private var token: String? = null
    private var target: String? = null

    /** Entry ID → attempt number for uploads in progress. */
    private val inFlight = mutableMapOf<String, Int>()
    private val reported = mutableMapOf<String, Pair<Int, Long>>()
    private var retryTimer: ScheduledFuture<*>? = null
    private var isInvalidated = false

    /** Where uploads go and the bearer token (from the current pairing). */
    fun setCredentials(target: String?, token: String?) {
        queue.execute {
            this.target = target
            this.token = token
        }
    }

    /**
     * Writes the framed payload and its metadata and returns the entry. Nothing is sent until
     * [kick]. With [failure], the entry starts out failed and is kept for when the link is renewed.
     */
    fun enqueue(timeline: ByteArray, movie: File, target: String, recordedAt: String, failure: Failure? = null): Entry =
        queue.submit<Entry> { write(timeline, movie, target, recordedAt, failure) }.get()

    /** Starts uploads for entries that are due (all pending entries when [force]). */
    fun kick(force: Boolean) {
        queue.execute { kickOnQueue(force) }
    }

    /** Clears permanent failures (after the link is renewed) and tries those entries again. */
    fun retryFailed() {
        queue.execute {
            for (entry in loadEntries()) {
                if (entry.failure == null) continue
                save(
                    entry.copy(
                        failure = if (entry.payloadBytes > maxPayloadBytes) Failure.TooLarge else null,
                        lastError = null,
                        nextAttemptAt = null,
                    ),
                )
            }
            kickOnQueue(true)
        }
    }

    fun entries(): List<Entry> = queue.submit<List<Entry>> { loadEntries() }.get()

    /** Whether any entry is still to be sent (not failed). */
    fun hasPending(): Boolean = entries().any { it.failure == null }

    /** Earliest time a pending entry is due, for scheduling [OutboxWorker]. */
    fun nextDue(): Long? = entries().filter { it.failure == null }.minOfOrNull { it.nextAttemptAt ?: 0L }

    /** Removes an entry and its payload. */
    fun remove(id: String) {
        queue.execute {
            inFlight.remove(id)
            payloadFile(id).delete()
            metadataFile(id).delete()
        }
    }

    /** Copies an entry's timeline and movie out of its payload into [folder] (for sharing). */
    fun extract(id: String, folder: File): Pair<File, File> =
        queue.submit<Pair<File, File>> { extractOnQueue(id, folder) }.get()

    /** Blocks until nothing is in flight (for [OutboxWorker]); returns early after [timeoutMs]. */
    fun awaitIdle(timeoutMs: Long) {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            val busy = queue.submit<Boolean> { inFlight.isNotEmpty() }.get()
            if (!busy) return
            Thread.sleep(200)
        }
    }

    /** Stops all uploads (the device was unpaired). Files stay on disk. */
    fun invalidate() {
        queue.execute {
            isInvalidated = true
            retryTimer?.cancel(false)
            retryTimer = null
            uploader.cancelAll()
        }
    }

    // Files

    private fun payloadFile(id: String) = File(directory, "$id.payload")
    private fun metadataFile(id: String) = File(directory, "$id.json")

    private fun write(timeline: ByteArray, movie: File, target: String, recordedAt: String, failure: Failure?): Entry {
        directory.mkdirs()
        val stamp = recordedAt.filter { it.isDigit() }
        val id = "${stamp.ifEmpty { "recording" }}-${UUID.randomUUID().toString().take(8).lowercase()}"
        val payload = payloadFile(id)
        try {
            FileOutputStream(payload).use { out ->
                val n = timeline.size
                out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
                out.write(timeline)
                movie.inputStream().use { it.copyTo(out, 1 shl 20) }
                out.fd.sync()
            }
        } catch (e: IOException) {
            payload.delete()
            throw e
        }
        val bytes = payload.length()
        val entry = Entry(
            id = id,
            target = target,
            recordedAt = recordedAt,
            createdAt = now(),
            payloadBytes = bytes,
            failure = if (bytes > maxPayloadBytes) Failure.TooLarge else failure,
        )
        save(entry)
        if (!metadataFile(id).exists()) {
            payload.delete()
            throw IOException("Could not write ${metadataFile(id)}")
        }
        return entry
    }

    private fun save(entry: Entry) {
        val file = metadataFile(entry.id)
        val tmp = File(directory, "${entry.id}.json.tmp")
        runCatching {
            tmp.writeText(entry.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(id: String): Entry? =
        runCatching { Entry.fromJson(JSONObject(metadataFile(id).readText())) }.getOrNull()

    /** Entries whose payload is on disk, oldest first. */
    private fun loadEntries(): List<Entry> =
        (directory.list() ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { load(it.removeSuffix(".json")) }
            .filter { payloadFile(it.id).exists() }
            .sortedBy { it.createdAt }

    private fun extractOnQueue(id: String, folder: File): Pair<File, File> {
        DataInputStream(payloadFile(id).inputStream().buffered()).use { input ->
            val length = input.readInt()
            if (length < 0) throw IOException("Corrupt payload")
            val timeline = ByteArray(length)
            input.readFully(timeline)
            folder.mkdirs()
            val timelineFile = File(folder, "$id.json").apply { writeBytes(timeline) }
            val movieFile = File(folder, "$id.mp4")
            movieFile.outputStream().use { input.copyTo(it, 1 shl 20) }
            return movieFile to timelineFile
        }
    }

    // Sending

    private fun kickOnQueue(force: Boolean) {
        if (isInvalidated) return
        val now = now()
        var nextDue: Long? = null
        for (entry in loadEntries()) {
            if (entry.failure != null || entry.id in inFlight) continue
            val due = entry.nextAttemptAt
            if (force || due == null || due <= now) {
                start(entry)
            } else {
                nextDue = min(nextDue ?: due, due)
            }
        }
        scheduleRetry(nextDue)
    }

    private fun start(entry: Entry) {
        val token = token?.takeIf { it.isNotEmpty() } ?: return
        val base = target ?: entry.target
        val updated = entry.copy(attempts = entry.attempts + 1, nextAttemptAt = null)
        inFlight[entry.id] = updated.attempts
        save(updated)
        onEvent(Event.Started(entry.id))
        val url = URL(base.trimEnd('/') + "/ingest")
        uploads.execute {
            val result = runCatching {
                uploader.upload(url, token, payloadFile(entry.id), entry.payloadBytes) { sent ->
                    queue.execute { sentBodyData(entry.id, sent, entry.payloadBytes) }
                }
            }
            queue.execute {
                val (status, body) = result.getOrElse { null to ByteArray(0) }
                finished(entry.id, updated.attempts, status, body, result.exceptionOrNull())
            }
        }
    }

    private fun sentBodyData(id: String, sent: Long, expected: Long) {
        if (id !in inFlight || expected <= 0) return
        val fraction = (sent.toDouble() / expected).coerceIn(0.0, 1.0)
        val percent = (fraction * 100).toInt()
        val now = now()
        if (!reportsProgress(percent, reported[id], now)) return
        reported[id] = percent to now
        onEvent(Event.Progress(id, fraction))
    }

    private fun scheduleRetry(at: Long?) {
        retryTimer?.cancel(false)
        retryTimer = null
        if (at == null) return
        val delay = max(at - now(), 500L)
        retryTimer = queue.schedule({ kickOnQueue(false) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun finished(id: String, attempt: Int, status: Int?, body: ByteArray, error: Throwable?) {
        reported.remove(id)
        if (inFlight[id] == attempt) inFlight.remove(id)
        // Removed while uploading, already handled, or torn down: the entry stays as it was.
        if (isInvalidated) return
        val entry = load(id) ?: return
        when (val outcome = classify(status)) {
            Outcome.Success -> {
                payloadFile(id).delete()
                metadataFile(id).delete()
                onEvent(Event.Sent(id, IngestReply.fromBody(body)))
            }
            is Outcome.Permanent -> {
                save(entry.copy(failure = outcome.failure, lastError = "HTTP ${status ?: 0}"))
                onEvent(Event.Failed(id, outcome.failure))
            }
            Outcome.Retry -> {
                val reason = error?.message ?: "HTTP ${status ?: 0}"
                val next = now() + backoffMs(entry.attempts)
                save(entry.copy(lastError = reason, nextAttemptAt = next))
                onEvent(Event.Retrying(id, reason, next))
                kickOnQueue(false)
            }
        }
    }

    companion object {
        /** The ingest server answers 413 above 95 MB. */
        const val DEFAULT_MAX_PAYLOAD_BYTES = 95_000_000L
        const val PROGRESS_INTERVAL_MS = 5_000L

        /**
         * Whether bytes sent up to [percent] are worth an event after [last] was reported: a new
         * whole percent, or a heartbeat every [PROGRESS_INTERVAL_MS] while it creeps.
         */
        fun reportsProgress(percent: Int, last: Pair<Int, Long>?, nowMs: Long): Boolean =
            last == null || percent != last.first || nowMs - last.second >= PROGRESS_INTERVAL_MS

        /** What to do after an upload attempt finished with [status] (null when no response arrived). */
        fun classify(status: Int?): Outcome = when {
            status == null -> Outcome.Retry
            status in 200..299 -> Outcome.Success
            status == 401 || status == 403 -> Outcome.Permanent(Failure.Unauthorized)
            status == 413 -> Outcome.Permanent(Failure.TooLarge)
            status == 408 || status == 429 -> Outcome.Retry
            status in 400..499 -> Outcome.Permanent(Failure.Rejected)
            else -> Outcome.Retry
        }

        /** 15 s, 30 s, 1 min, … up to 30 min. */
        fun backoffMs(attempts: Int): Long =
            min(15_000.0 * 2.0.pow(max(attempts, 1) - 1.0), 30 * 60 * 1000.0).toLong()
    }
}

/** Streams a payload file to `POST {base}/ingest` with the bearer token. */
class HttpUploader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : Outbox.Uploader {
    override fun upload(
        url: URL,
        token: String,
        payload: File,
        length: Long,
        onProgress: (sent: Long) -> Unit,
    ): Pair<Int?, ByteArray> {
        val connection = url.openConnection() as HttpURLConnection
        active += connection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setFixedLengthStreamingMode(length)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-Scrawl-Token", token)
            connection.outputStream.use { out ->
                payload.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readUpTo(64 * 1024) } ?: ByteArray(0)
            return status to body
        } finally {
            active -= connection
            connection.disconnect()
        }
    }

    private val active: MutableSet<HttpURLConnection> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun cancelAll() {
        // Disconnecting from another thread makes the blocked write or read throw.
        for (connection in active.toList()) runCatching { connection.disconnect() }
    }
}
