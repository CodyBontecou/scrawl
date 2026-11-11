package dev.kitlangton.scrawl

import dev.kitlangton.scrawl.DeliveryTracker.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestReplyTest {
    private fun reply(json: String) = IngestReply.fromBody(json.toByteArray())

    @Test
    fun deliveredOnlyWhenTheMacSaysSo() {
        assertEquals(IngestReply.Delivered("UP DOWN"), reply("""{"ok":true,"delivered":true,"state":"sent","sessionTitle":"UP DOWN"}"""))
        assertEquals(
            IngestReply.Queued("UP DOWN"),
            reply("""{"ok":true,"delivered":false,"state":"queued","sessionTitle":"UP DOWN","error":"OpenCode API failed"}"""),
        )
        assertEquals(IngestReply.Processing("UP DOWN"), reply("""{"ok":true,"delivered":false,"state":"processing","sessionTitle":"UP DOWN"}"""))
        assertEquals(IngestReply.Saved, reply("""{"ok":true,"delivered":false,"state":"saved","sessionTitle":"Mac"}"""))
        // A state this build doesn't know is still on the Mac, not delivered.
        assertEquals(IngestReply.Saved, reply("""{"ok":true,"delivered":false,"state":"later","sessionTitle":"UP DOWN"}"""))
    }

    @Test
    fun serversFromBeforeDeliveredReadAsSent() {
        assertEquals(IngestReply.Delivered("Problems · Ledger"), reply("""{"ok":true,"sessionTitle":"Problems · Ledger","item":"I-1"}"""))
        assertEquals(IngestReply.Delivered("Scrawl"), reply("""{"ok":true,"sessionTitle":""}"""))
        assertEquals(IngestReply.Delivered("Scrawl"), reply(""))
        assertEquals(IngestReply.Delivered("Scrawl"), reply("not json"))
    }

    @Test
    fun usbMarkersFallBackToTheSessionName() {
        assertEquals(IngestReply.Delivered("UP DOWN"), IngestReply.fromSentMarker("UP DOWN", null))
        val queued = """{"ok":true,"delivered":false,"state":"queued","sessionTitle":"UP DOWN"}""".toByteArray()
        assertEquals(IngestReply.Queued("UP DOWN"), IngestReply.fromSentMarker("UP DOWN", queued))
        assertEquals(IngestReply.Delivered("UP DOWN"), IngestReply.fromSentMarker("UP DOWN", "{}".toByteArray()))
    }
}

class DeliveryTrackerTest {
    private val t0 = 800_000_000_000L
    private val id = "20261015-abc"
    private fun at(seconds: Double) = t0 + (seconds * 1000).toLong()

    @Test
    fun sendingStaysSendingWhileBytesMove() {
        val tracker = DeliveryTracker(id, true, t0)
        assertEquals(Display.Sending(null), tracker.display(t0))
        tracker.apply(Outbox.Event.Started(id), at(0.1))
        assertEquals(Display.Sending(null), tracker.display(at(4.5)))
        var second = 5.0
        while (second <= 120) {
            tracker.apply(Outbox.Event.Progress(id, second / 200), at(second))
            second += 5
        }
        assertEquals(Display.Sending(0.6), tracker.display(at(120.0)))
        tracker.apply(Outbox.Event.Sent(id, IngestReply.Delivered("Problems · Ledger")), at(130.0))
        assertEquals(Display.Sent(IngestReply.Delivered("Problems · Ledger")), tracker.display(at(130.0)))
    }

    @Test
    fun noProgressOnAWorkingNetworkIsStillSendingNotQueued() {
        val tracker = DeliveryTracker(id, true, t0)
        tracker.apply(Outbox.Event.Progress(id, 0.3), at(2.0))
        assertEquals(at(32.0), tracker.nextChange(at(2.0)))
        assertEquals(Display.Sending(0.3), tracker.display(at(31.9)))
        assertEquals(Display.StillSending(0.3), tracker.display(at(32.0)))
        assertEquals(Display.StillSending(0.3), tracker.display(at(600.0)))
        assertNull(tracker.nextChange(at(40.0)))
        tracker.apply(Outbox.Event.Progress(id, 0.31), at(41.0))
        assertEquals(Display.Sending(0.31), tracker.display(at(41.0)))
    }

    @Test
    fun anUnsatisfiedPathIsQueuedAndNeverStalls() {
        val tracker = DeliveryTracker(id, false, t0)
        tracker.apply(Outbox.Event.Started(id), t0)
        assertEquals(Display.Queued, tracker.display(t0))
        assertEquals(Display.Queued, tracker.display(at(300.0)))
        assertNull(tracker.nextChange(at(300.0)))
        tracker.setPath(true, at(300.0))
        assertEquals(Display.Sending(null), tracker.display(at(300.0)))
        assertEquals(Display.Sending(null), tracker.display(at(329.0)))
        tracker.apply(Outbox.Event.Progress(id, 0.5), at(310.0))
        tracker.setPath(false, at(311.0))
        assertEquals(Display.Queued, tracker.display(at(311.0)))
    }

    @Test
    fun aFailedAttemptIsQueuedUntilTheRetryStarts() {
        val tracker = DeliveryTracker(id, true, t0)
        tracker.apply(Outbox.Event.Started(id), t0)
        tracker.apply(Outbox.Event.Progress(id, 0.8), at(1.0))
        tracker.apply(Outbox.Event.Retrying(id, "HTTP 503", at(16.0)), at(2.0))
        assertEquals(Display.Queued, tracker.display(at(2.0)))
        tracker.setPath(true, at(5.0))
        assertEquals(Display.Queued, tracker.display(at(60.0)))
        tracker.apply(Outbox.Event.Started(id), at(61.0))
        assertEquals(Display.Sending(null), tracker.display(at(61.0)))
        tracker.apply(Outbox.Event.Sent(id, IngestReply.Delivered("Problems")), at(62.0))
        assertEquals(Display.Sent(IngestReply.Delivered("Problems")), tracker.display(at(62.0)))
    }

    @Test
    fun permanentFailuresAreShownAsFailures() {
        for (failure in Outbox.Failure.entries) {
            val tracker = DeliveryTracker(id, true, t0)
            tracker.apply(Outbox.Event.Failed(id, failure), at(1.0))
            assertEquals(Display.Failed(failure), tracker.display(at(1.0)))
            assertTrue(tracker.isFinished)
        }
    }

    @Test
    fun otherEntriesAndLateEventsAreIgnored() {
        val tracker = DeliveryTracker(id, true, t0)
        tracker.apply(Outbox.Event.Retrying("other", "HTTP 503", at(15.0)), at(1.0))
        tracker.apply(Outbox.Event.Sent("other", IngestReply.Delivered("Elsewhere")), at(1.0))
        assertEquals(Display.Sending(null), tracker.display(at(1.0)))
        tracker.apply(Outbox.Event.Sent(id, IngestReply.Delivered("Problems")), at(2.0))
        tracker.apply(Outbox.Event.Retrying(id, "late", at(20.0)), at(3.0))
        tracker.setPath(false, at(4.0))
        assertEquals(Display.Sent(IngestReply.Delivered("Problems")), tracker.display(at(4.0)))
    }
}
