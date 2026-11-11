package dev.kitlangton.scrawl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.Collections

/** Stands in for the ingest endpoint. */
class StubServer(private val directory: File) : Outbox.Uploader {
    data class Reply(
        val status: Int = 200,
        val body: String = """{"ok":true,"sessionTitle":"Problems · Ledger","item":"I-1"}""",
        /** Fail the connection instead of answering (after the server has read the upload). */
        val error: IOException? = null,
        val delayMs: Long = 0,
    )

    data class Received(val url: URL, val token: String, val length: Long, val body: ByteArray, val filesOnDisk: List<String>)

    private val replies = Collections.synchronizedList(mutableListOf<Reply>())
    val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())

    fun reset(vararg replies: Reply) {
        this.replies.clear(); this.replies.addAll(replies); received.clear()
    }

    override fun upload(url: URL, token: String, payload: File, length: Long, onProgress: (Long) -> Unit): Pair<Int?, ByteArray> {
        val body = payload.readBytes()
        onProgress(body.size.toLong())
        received += Received(url, token, length, body, (directory.list() ?: emptyArray()).sorted())
        val reply = synchronized(replies) { if (replies.isEmpty()) Reply() else replies.removeAt(0) }
        if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
        reply.error?.let { throw it }
        return reply.status to reply.body.toByteArray()
    }
}

/** Collects the outbox's events. */
class EventLog {
    private val events = Collections.synchronizedList(mutableListOf<Outbox.Event>())
    val delivery = DeliveryProbe()
    fun append(event: Outbox.Event) {
        events += event
        delivery.apply(event)
    }

    val all: List<Outbox.Event> get() = synchronized(events) { events.toList() }
}

/** Feeds a [DeliveryTracker] the outbox's events, keeping every change in what the strip would say. */
class DeliveryProbe {
    private var tracker: DeliveryTracker? = null
    private var pathSatisfied = true
    private val log = mutableListOf<DeliveryTracker.Display>()

    @Synchronized fun track(entry: String) {
        tracker = DeliveryTracker(entry, pathSatisfied, System.currentTimeMillis()); note()
    }

    @Synchronized fun apply(event: Outbox.Event) {
        tracker?.apply(event, System.currentTimeMillis()); note()
    }

    @Synchronized fun setPath(satisfied: Boolean) {
        pathSatisfied = satisfied
        tracker?.setPath(satisfied, System.currentTimeMillis()); note()
    }

    val current: DeliveryTracker.Display? @Synchronized get() {
        note(); return log.lastOrNull()
    }

    /** The states in order, ignoring progress within "Sending…". */
    val states: List<String> @Synchronized get() = log.map {
        when (it) {
            is DeliveryTracker.Display.Sending -> "sending"
            is DeliveryTracker.Display.StillSending -> "stillSending"
            DeliveryTracker.Display.Queued -> "queued"
            is DeliveryTracker.Display.Sent -> "sent"
            is DeliveryTracker.Display.Failed -> "failed"
        }
    }.fold(mutableListOf()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }

    private fun note() {
        val display = tracker?.display(System.currentTimeMillis()) ?: return
        if (log.lastOrNull() != display) log += display
    }
}

fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

class OutboxTest {
    private lateinit var root: File
    private lateinit var directory: File
    private lateinit var movie: File
    private lateinit var server: StubServer
    private val events = EventLog()
    private val target = "https://problems.example.com/api/scrawl/ledger"
    private val timeline = """{"recordedAt":"2026-10-14T18:00:00Z","app":"Ledger"}""".toByteArray()
    private val movieBytes = ByteArray(300_000) { (it % 251).toByte() }
    private val delivered = IngestReply.Delivered("Problems · Ledger")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scrawl-outbox-tests").toFile()
        directory = File(root, "ScrawlOutbox")
        movie = File(root, "movie.mp4").apply { writeBytes(movieBytes) }
        server = StubServer(directory)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun makeOutbox(maxPayloadBytes: Long = Outbox.DEFAULT_MAX_PAYLOAD_BYTES) =
        Outbox(directory, server, maxPayloadBytes) { events.append(it) }.apply { setCredentials(target, "pbcap_test") }

    private val expectedPayload: ByteArray
        get() {
            val n = timeline.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + timeline + movieBytes
        }

    private val filesOnDisk: List<String> get() = (directory.list() ?: emptyArray()).sorted()
    private fun enqueue(outbox: Outbox, failure: Outbox.Failure? = null) =
        outbox.enqueue(timeline, movie, target, "2026-10-14T18:00:00Z", failure)

    private fun sent(id: String, reply: IngestReply = delivered) = events.all.contains(Outbox.Event.Sent(id, reply))
    private fun retries() = events.all.count { it is Outbox.Event.Retrying }

    @Test
    fun persistsTheFramedPayloadBeforeSendingAndDeletesItAfter2xx() {
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        assertEquals(listOf("${entry.id}.json", "${entry.id}.payload"), filesOnDisk)
        assertTrue(File(directory, "${entry.id}.payload").readBytes().contentEquals(expectedPayload))
        assertEquals(expectedPayload.size.toLong(), entry.payloadBytes)
        assertTrue(server.received.isEmpty())

        outbox.kick(false)
        assertTrue(eventually { sent(entry.id) })
        val received = server.received.first()
        assertEquals(URL("$target/ingest"), received.url)
        assertEquals("pbcap_test", received.token)
        assertEquals(expectedPayload.size.toLong(), received.length)
        assertTrue(received.filesOnDisk.contains("${entry.id}.payload"))
        assertTrue(received.body.contentEquals(expectedPayload))
        assertTrue(eventually { filesOnDisk.isEmpty() })
    }

    @Test
    fun aMacThatKeptItButCouldNotForwardItIsNotAskedAgain() {
        server.reset(StubServer.Reply(body = """{"ok":true,"delivered":false,"state":"queued","sessionTitle":"UP DOWN"}"""))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        outbox.kick(false)
        assertTrue(eventually { sent(entry.id, IngestReply.Queued("UP DOWN")) })
        assertTrue(eventually { filesOnDisk.isEmpty() })
        outbox.kick(true)
        Thread.sleep(200)
        assertEquals(1, server.received.size)
    }

    @Test
    fun transientStatusesAreRetriedWithBackoffAndTheFileIsKept() {
        for (status in listOf(500, 502, 503, 429, 408)) {
            tearDown(); setUp()
            server.reset(StubServer.Reply(status = status))
            val outbox = makeOutbox()
            val entry = enqueue(outbox)
            outbox.kick(false)
            assertTrue(eventually { events.all.any { it is Outbox.Event.Retrying && it.id == entry.id } })
            val kept = outbox.entries().first()
            assertEquals(1, kept.attempts)
            assertEquals("HTTP $status", kept.lastError)
            assertNull(kept.failure)
            assertTrue((kept.nextAttemptAt ?: 0) > System.currentTimeMillis())
            assertEquals(2, filesOnDisk.size)

            outbox.kick(false)
            Thread.sleep(200)
            assertEquals(1, server.received.size)

            outbox.kick(true)
            assertTrue(eventually { sent(entry.id) })
            assertEquals(2, server.received.size)
            assertTrue(eventually { filesOnDisk.isEmpty() })
            outbox.invalidate()
        }
    }

    @Test
    fun noNetworkIsRetried() {
        server.reset(StubServer.Reply(error = IOException("offline")), StubServer.Reply(error = IOException("timed out")))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        outbox.kick(false)
        assertTrue(eventually { retries() == 1 })
        outbox.kick(true)
        assertTrue(eventually { retries() == 2 })
        assertEquals(2, outbox.entries().first().attempts)
        outbox.kick(true)
        assertTrue(eventually { sent(entry.id) })
        assertTrue(eventually { filesOnDisk.isEmpty() })
    }

    @Test
    fun permanentFailuresKeepTheFileAndStopRetrying() {
        for ((status, failure) in listOf(401 to Outbox.Failure.Unauthorized, 403 to Outbox.Failure.Unauthorized, 413 to Outbox.Failure.TooLarge)) {
            tearDown(); setUp()
            server.reset(StubServer.Reply(status = status, body = """{"error":"no"}"""))
            val outbox = makeOutbox()
            val entry = enqueue(outbox)
            outbox.kick(false)
            assertTrue(eventually { events.all.contains(Outbox.Event.Failed(entry.id, failure)) })
            assertEquals(listOf("${entry.id}.json", "${entry.id}.payload"), filesOnDisk)
            assertEquals(failure, outbox.entries().first().failure)

            outbox.kick(true)
            Thread.sleep(200)
            assertEquals(1, server.received.size)

            outbox.retryFailed()
            assertTrue(eventually { sent(entry.id) })
            assertTrue(eventually { filesOnDisk.isEmpty() })
            outbox.invalidate()
        }
    }

    @Test
    fun aLostReplyIsSentAgainWithTheSameBytes() {
        server.reset(StubServer.Reply(error = IOException("connection lost")), StubServer.Reply(status = 200))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        outbox.kick(false)
        assertTrue(eventually { retries() == 1 })
        assertEquals(2, filesOnDisk.size)
        outbox.kick(true)
        assertTrue(eventually { sent(entry.id) })
        val bodies = server.received.map { it.body }
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it.contentEquals(expectedPayload) })
        assertTrue(eventually { filesOnDisk.isEmpty() })
    }

    @Test
    fun anEntryIsNeverUploadingTwiceAtOnce() {
        server.reset(StubServer.Reply(delayMs = 600))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        repeat(5) { outbox.kick(true) }
        Thread.sleep(250)
        outbox.kick(true)
        assertTrue(eventually { sent(entry.id) })
        Thread.sleep(200)
        assertEquals(1, server.received.size)
    }

    @Test
    fun payloadsOverTheLimitAreNeverSent() {
        val outbox = makeOutbox(maxPayloadBytes = 100_000)
        val entry = enqueue(outbox)
        assertEquals(Outbox.Failure.TooLarge, entry.failure)
        outbox.kick(true)
        outbox.retryFailed()
        Thread.sleep(300)
        assertTrue(server.received.isEmpty())
        assertEquals(2, filesOnDisk.size)
    }

    @Test
    fun aRevokedLinkQueuesTheEntryAsFailedWithoutSending() {
        val outbox = makeOutbox()
        val entry = enqueue(outbox, Outbox.Failure.Unauthorized)
        outbox.kick(true)
        Thread.sleep(200)
        assertTrue(server.received.isEmpty())
        assertEquals(Outbox.Failure.Unauthorized, outbox.entries().first().failure)
        outbox.retryFailed()
        assertTrue(eventually { sent(entry.id) })
    }

    @Test
    fun entriesSurviveARestartAndAreSentBySeparateOutboxes() {
        server.reset(StubServer.Reply(status = 503))
        val first = makeOutbox()
        val entry = enqueue(first)
        first.kick(false)
        assertTrue(eventually { retries() == 1 })
        first.invalidate()

        val second = makeOutbox()
        assertEquals(listOf(entry.id), second.entries().map { it.id })
        second.kick(true)
        assertTrue(eventually { sent(entry.id) })
        assertTrue(eventually { filesOnDisk.isEmpty() })
    }

    @Test
    fun sharingExtractsTheOriginalTimelineAndMovie() {
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        val (movieFile, timelineFile) = outbox.extract(entry.id, File(root, "share"))
        assertTrue(timelineFile.readBytes().contentEquals(timeline))
        assertTrue(movieFile.readBytes().contentEquals(movieBytes))
    }

    // What the strip says

    @Test
    fun aSlowUploadIsSendingUntilItLandsNeverQueued() {
        server.reset(StubServer.Reply(delayMs = 1_500))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        events.delivery.track(entry.id)
        outbox.kick(true)
        Thread.sleep(1_000)
        assertTrue(events.delivery.current is DeliveryTracker.Display.Sending)
        assertTrue(eventually { sent(entry.id) })
        assertEquals(listOf("sending", "sent"), events.delivery.states)
        assertEquals(DeliveryTracker.Display.Sent(delivered), events.delivery.current)
        assertEquals(Outbox.Event.Started(entry.id), events.all.first())
    }

    @Test
    fun anUnsatisfiedPathIsQueuedThenSendingWhenItsBack() {
        server.reset(StubServer.Reply(delayMs = 1_000))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        events.delivery.setPath(false)
        events.delivery.track(entry.id)
        outbox.kick(true)
        assertTrue(eventually { events.all.contains(Outbox.Event.Started(entry.id)) })
        assertEquals(DeliveryTracker.Display.Queued, events.delivery.current)
        events.delivery.setPath(true)
        assertTrue(eventually { sent(entry.id) })
        assertEquals(listOf("queued", "sending", "sent"), events.delivery.states)
    }

    @Test
    fun aFailedAttemptIsQueuedThenSendingOnRetryThenSent() {
        server.reset(StubServer.Reply(status = 503), StubServer.Reply(delayMs = 500))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        events.delivery.track(entry.id)
        outbox.kick(true)
        assertTrue(eventually { retries() == 1 })
        assertEquals(DeliveryTracker.Display.Queued, events.delivery.current)
        outbox.kick(true)
        assertTrue(eventually { sent(entry.id) })
        assertEquals(listOf("sending", "queued", "sending", "sent"), events.delivery.states)
    }

    @Test
    fun otherClientErrorsFailWithoutQueueing() {
        server.reset(StubServer.Reply(status = 422))
        val outbox = makeOutbox()
        val entry = enqueue(outbox)
        events.delivery.track(entry.id)
        outbox.kick(true)
        assertTrue(eventually { events.all.contains(Outbox.Event.Failed(entry.id, Outbox.Failure.Rejected)) })
        assertEquals(listOf("sending", "failed"), events.delivery.states)
        assertEquals(DeliveryTracker.Display.Failed(Outbox.Failure.Rejected), events.delivery.current)
    }

    @Test
    fun classification() {
        assertEquals(Outbox.Outcome.Success, Outbox.classify(200))
        assertEquals(Outbox.Outcome.Success, Outbox.classify(201))
        assertEquals(Outbox.Outcome.Permanent(Outbox.Failure.Unauthorized), Outbox.classify(401))
        assertEquals(Outbox.Outcome.Permanent(Outbox.Failure.Unauthorized), Outbox.classify(403))
        assertEquals(Outbox.Outcome.Permanent(Outbox.Failure.TooLarge), Outbox.classify(413))
        assertEquals(Outbox.Outcome.Retry, Outbox.classify(500))
        assertEquals(Outbox.Outcome.Retry, Outbox.classify(429))
        assertEquals(Outbox.Outcome.Retry, Outbox.classify(408))
        assertEquals(Outbox.Outcome.Permanent(Outbox.Failure.Rejected), Outbox.classify(400))
        assertEquals(Outbox.Outcome.Permanent(Outbox.Failure.Rejected), Outbox.classify(404))
        assertEquals(Outbox.Outcome.Permanent(Outbox.Failure.Rejected), Outbox.classify(411))
        assertEquals(Outbox.Outcome.Retry, Outbox.classify(null))
        assertTrue(Outbox.reportsProgress(1, null, 0))
        val last = 41 to 0L
        assertTrue(Outbox.reportsProgress(42, last, 100))
        assertFalse(Outbox.reportsProgress(41, last, 4_900))
        assertTrue(Outbox.reportsProgress(41, last, 5_000))
        assertEquals(15_000L, Outbox.backoffMs(1))
        assertEquals(30_000L, Outbox.backoffMs(2))
        assertEquals(1_800_000L, Outbox.backoffMs(20))
    }

    /** Unpairing aborts an upload mid-stream, like URLSession's invalidateAndCancel, and keeps it. */
    @Test
    fun unpairingCancelsAnUploadInProgress() {
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val uploader = object : Outbox.Uploader {
            override fun upload(url: java.net.URL, token: String, payload: File, length: Long, onProgress: (Long) -> Unit): Pair<Int?, ByteArray> {
                started.countDown()
                if (!cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS)) return 200 to ByteArray(0)
                throw java.io.IOException("Socket closed")
            }

            override fun cancelAll() = cancelled.countDown()
        }
        val outbox = Outbox(directory, uploader) { events.append(it) }.apply { setCredentials(target, "pbcap_test") }
        val entry = enqueue(outbox)
        outbox.kick(false)
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        outbox.invalidate()
        assertTrue("cancelled", cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(300)
        assertTrue("kept on disk", File(directory, "${entry.id}.payload").exists())
        assertTrue("no Sent", events.all.none { it is Outbox.Event.Sent })
    }

    /** HttpUploader.cancelAll aborts a real connection blocked on a server that stopped reading. */
    @Test
    fun httpUploaderCancelAllAbortsABlockedUpload() {
        val server = java.net.ServerSocket(0)
        val held = mutableListOf<java.net.Socket>()
        val acceptor = Thread { runCatching { while (true) held += server.accept() } }.apply { isDaemon = true; start() }
        val payload = File(directory, "big.payload").apply { parentFile.mkdirs(); writeBytes(ByteArray(32 * 1024 * 1024)) }
        val uploader = HttpUploader()
        var error: Throwable? = null
        val done = java.util.concurrent.CountDownLatch(1)
        Thread {
            error = runCatching { uploader.upload(java.net.URL("http://127.0.0.1:${server.localPort}/ingest"), "t", payload, payload.length()) {} }.exceptionOrNull()
            done.countDown()
        }.start()
        Thread.sleep(800)
        assertEquals("still blocked before the cancel", 1L, done.count)
        uploader.cancelAll()
        assertTrue("returned after the cancel", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("as an error: $error", error != null)
        server.close(); held.forEach { it.close() }; acceptor.interrupt()
    }
}
