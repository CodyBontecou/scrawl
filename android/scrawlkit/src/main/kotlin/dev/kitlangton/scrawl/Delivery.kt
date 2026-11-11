package dev.kitlangton.scrawl

import org.json.JSONObject

/**
 * What a Scrawl server said became of a recording it took: the JSON of its 2xx, or the `.delivery`
 * marker a Mac writes back over USB (adb).
 *
 * The Mac answers once it has built the bundle and tried to queue it into the agent session:
 * `{"ok":true,"delivered":bool,"state":"sent"|"queued"|"saved"|"processing","sessionTitle":…}`.
 * Servers from before `delivered` answered as soon as the files arrived; that reads as delivered.
 */
sealed class IngestReply {
    /** Queued into the agent session [sessionTitle]. */
    data class Delivered(val sessionTitle: String) : IngestReply()

    /** On the Mac, which couldn't reach the session and keeps trying on its own. */
    data class Queued(val sessionTitle: String) : IngestReply()

    /** On the Mac, still being built or sent when it answered. */
    data class Processing(val sessionTitle: String) : IngestReply()

    /** On the Mac and going no further (it has no session for this app). */
    data object Saved : IngestReply()

    val isDelivered: Boolean get() = this is Delivered

    companion object {
        /** Reads a 2xx response body. */
        fun fromBody(body: ByteArray): IngestReply = fromJson(parse(body), "Scrawl")

        /**
         * Reads the USB markers: `.sent` holds the session's name; `.delivery`, written first by
         * Macs that know it, holds the same JSON as an HTTP reply.
         */
        fun fromSentMarker(title: String, delivery: ByteArray?): IngestReply =
            fromJson(delivery?.let(::parse) ?: JSONObject(), title)

        private fun parse(bytes: ByteArray): JSONObject =
            runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }

        private fun fromJson(json: JSONObject, fallbackTitle: String): IngestReply {
            // As Swift casts JSONSerialization's values: only strings are titles and states, and a
            // number bridges to Bool only when it is exactly 0 or 1.
            val title = (json.opt("sessionTitle") as? String)?.takeIf { it.isNotEmpty() } ?: fallbackTitle
            val delivered = when (val raw = json.opt("delivered")) {
                is Boolean -> raw
                is Number -> when (raw.toDouble()) { 0.0 -> false; 1.0 -> true; else -> null }
                else -> null
            }
            if (delivered == null || delivered) return Delivered(title)
            return when (json.opt("state") as? String) {
                "queued" -> Queued(title)
                "processing" -> Processing(title)
                else -> Saved
            }
        }
    }
}

/**
 * What the strip says about one recording on its way to an HTTPS endpoint, worked out from the
 * outbox's events and whether the network is available. Time is passed in, so it's a plain value
 * that tests can drive.
 *
 * - While an upload is running on a satisfied path it's "Sending…", however long that takes.
 * - With no byte progress for [STALL_AFTER_MS] on a satisfied path it's "Still sending…", never
 *   "Queued": the phone is online, the upload is just slow.
 * - "Queued" is only for an unsatisfied path or an attempt that failed and is waiting to retry.
 */
class DeliveryTracker(val entry: String, pathSatisfied: Boolean, nowMs: Long) {
    sealed class Display {
        /** [progress] is 0…1 once the upload reports bytes sent. */
        data class Sending(val progress: Double?) : Display()
        data class StillSending(val progress: Double?) : Display()
        data object Queued : Display()
        data class Sent(val reply: IngestReply) : Display()
        data class Failed(val failure: Outbox.Failure) : Display()
    }

    private sealed class Phase {
        data object Uploading : Phase()
        data object WaitingToRetry : Phase()
        data class Sent(val reply: IngestReply) : Phase()
        data class Failed(val failure: Outbox.Failure) : Phase()
    }

    var pathSatisfied: Boolean = pathSatisfied
        private set
    private var phase: Phase = Phase.Uploading
    private var progress: Double? = null

    /** The last sign the upload is moving: its start, bytes sent, or the network coming back. */
    private var lastActivity: Long = nowMs

    val isFinished: Boolean get() = phase is Phase.Sent || phase is Phase.Failed

    fun apply(event: Outbox.Event, nowMs: Long) {
        if (event.id != entry || isFinished) return
        when (event) {
            is Outbox.Event.Started -> {
                phase = Phase.Uploading; progress = null; lastActivity = nowMs
            }
            is Outbox.Event.Progress -> {
                phase = Phase.Uploading; progress = event.fraction; lastActivity = nowMs
            }
            is Outbox.Event.Retrying -> phase = Phase.WaitingToRetry
            is Outbox.Event.Sent -> phase = Phase.Sent(event.reply)
            is Outbox.Event.Failed -> phase = Phase.Failed(event.failure)
        }
    }

    fun setPath(satisfied: Boolean, nowMs: Long) {
        // Waiting for the network isn't a stall: the clock starts again when it's back.
        if (satisfied && !pathSatisfied) lastActivity = nowMs
        pathSatisfied = satisfied
    }

    fun display(nowMs: Long): Display = when (val p = phase) {
        is Phase.Sent -> Display.Sent(p.reply)
        is Phase.Failed -> Display.Failed(p.failure)
        Phase.WaitingToRetry -> Display.Queued
        Phase.Uploading -> when {
            !pathSatisfied -> Display.Queued
            nowMs - lastActivity >= STALL_AFTER_MS -> Display.StillSending(progress)
            else -> Display.Sending(progress)
        }
    }

    /** When [display] will change without a new event (an upload stalling), if it will. */
    fun nextChange(nowMs: Long): Long? {
        if (phase != Phase.Uploading || !pathSatisfied) return null
        val stall = lastActivity + STALL_AFTER_MS
        return if (stall > nowMs) stall else null
    }

    companion object {
        const val STALL_AFTER_MS = 30_000L
    }
}

/** Reads at most [limit] bytes (`InputStream.readNBytes` is API 33; ScrawlKit runs from 26). */
internal fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
