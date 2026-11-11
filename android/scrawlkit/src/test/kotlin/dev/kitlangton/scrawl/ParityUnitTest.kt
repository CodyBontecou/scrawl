package dev.kitlangton.scrawl

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.abs

/** Setup links and launch overrides (checklist 1–6, 11). */
class ScrawlConfigTest {
    private fun configure(url: String) = (ScrawlConfig.parseSetupURL(url) as ScrawlConfig.SetupAction.Configure).config

    @Test
    fun setupLinksNormalizeAndDedupe() {
        val c = configure("voxmd://scrawl?url=http%3A%2F%2F100.64.0.12%3A47777%2F%2F&url=http%3A%2F%2F192.168.1.20%3A47777&url=http%3A%2F%2F100.64.0.12%3A47777&url=ftp%3A%2F%2Fx&token=%20abc%20&session=%20ses_1%20")
        assertEquals(listOf("http://100.64.0.12:47777", "http://192.168.1.20:47777"), c.urls)
        assertEquals("abc", c.token)
        assertEquals("ses_1", c.sessionID)
        assertTrue(c.isValid)
        // Any scheme, host scrawl or the legacy tether.
        assertTrue(ScrawlConfig.parseSetupURL("other://tether?url=https%3A%2F%2Fp.example.com%2Fapi&token=t") is ScrawlConfig.SetupAction.Configure)
    }

    @Test
    fun resetWinsAndInvalidLinksAreRejected() {
        assertEquals(ScrawlConfig.SetupAction.Reset, ScrawlConfig.parseSetupURL("voxmd://scrawl?url=http%3A%2F%2Fa%3A1&token=t&reset=TRUE"))
        assertEquals(ScrawlConfig.SetupAction.Reset, ScrawlConfig.parseSetupURL("voxmd://scrawl?reset=1"))
        assertNull(ScrawlConfig.parseSetupURL("voxmd://scrawl?token=t"))
        assertNull(ScrawlConfig.parseSetupURL("voxmd://scrawl?url=http%3A%2F%2Fa%3A1"))
        assertNull(ScrawlConfig.parseSetupURL("voxmd://other?url=http%3A%2F%2Fa%3A1&token=t"))
        assertNull(ScrawlConfig.parseHTTPBaseURL("file:///x"))
        assertNull(ScrawlConfig.parseHTTPBaseURL("http://"))
    }

    @Test
    fun launchOverridesLayerOverTheStoredConfig() {
        val stored = ScrawlConfig.of(listOf("http://stored:1"), "stored", "ses_stored")
        val resolved = ScrawlConfig.resolved(
            listOf("-scrawl-url", "http://a:1,http://b:2", "-scrawl-token", " argtoken "),
            mapOf("SCRAWL_URL" to "http://c:3", "SCRAWL_SESSION" to "ses_env"),
            stored,
        )!!
        assertEquals(listOf("http://a:1", "http://b:2", "http://c:3"), resolved.urls)
        assertEquals("argtoken", resolved.token)
        assertEquals("ses_env", resolved.sessionID)
        // Nothing overridden: the stored config.
        assertEquals(stored, ScrawlConfig.resolved(emptyList(), emptyMap(), stored))
        // No token anywhere: not configured.
        assertNull(ScrawlConfig.resolved(listOf("-scrawl-url", "http://a:1"), emptyMap(), null))
    }
}

/** Screen and context stacks, and their timeline keys (checklist 71–73). */
class ScrawlAppContextTest {
    private var seconds = 0.0
    private val context = ScrawlAppContext { seconds }

    @Test
    fun theLatestSetterStillPresentWinsAndChangesAreTimed() {
        val home = UUID.randomUUID()
        val sheet = UUID.randomUUID()
        context.setScreen("Home", home, promote = true)
        context.setContext("session", "ses_1", ScrawlAppContext.manualToken, promote = true)
        context.begin()
        seconds = 1.2341
        context.setScreen("Sheet", sheet, promote = true)
        seconds = 2.0
        context.setScreen("Sheet 2", sheet, promote = false)
        seconds = 3.0
        context.setScreen(null, sheet, promote = false)
        context.setContext("session", null, ScrawlAppContext.manualToken, promote = false)
        assertEquals("Home", context.currentScreen)
        val info = context.end()
        val screens = info["screens"] as JSONArray
        assertEquals(JSONObject().put("t", 0.0).put("name", "Home").toString(), screens.getJSONObject(0).toString())
        assertEquals("Sheet", screens.getJSONObject(1).getString("name"))
        assertEquals(1.234, screens.getJSONObject(1).getDouble("t"), 1e-9)
        assertEquals("Sheet 2", screens.getJSONObject(2).getString("name"))
        assertEquals("Home", screens.getJSONObject(3).getString("name"))
        assertEquals("ses_1", (info["context"] as JSONObject).getString("session"))
        val change = (info["contextChanges"] as JSONArray).getJSONObject(0)
        assertTrue(change.isNull("value"))
        assertEquals("session", change.getString("key"))
    }

    @Test
    fun blankValuesClearAndNothingIsLoggedOutsideRecordings() {
        val token = UUID.randomUUID()
        context.setContext("  ", "x", token, promote = true)
        context.setContext("k", "  ", token, promote = true)
        assertTrue(context.currentValues.isEmpty())
        context.setScreen("A", token, promote = true)
        val info = context.end()
        assertEquals(0, (info["screens"] as JSONArray).length())
    }
}

/** Label cleaning (checklist 62) and microphone timestamps (checklist 42). */
class SmallRulesTest {
    @Test
    fun labelsCollapseWhitespaceAndCapAt48() {
        assertEquals("Save draft", HitLabels.cleaned("  Save \n  draft "))
        assertNull(HitLabels.cleaned("   "))
        val long = "x".repeat(60)
        assertEquals("x".repeat(48) + "…", HitLabels.cleaned(long))
    }

    @Test
    fun audioRunsOnContiguouslyAndResyncsAfterRealGaps() {
        val ms = 1_000_000L
        assertEquals(500 * ms, Microphone.stamp(Long.MIN_VALUE, 500 * ms))
        // Within 50 ms either way: contiguous.
        assertEquals(1_000 * ms, Microphone.stamp(1_000 * ms, 1_030 * ms))
        assertEquals(1_000 * ms, Microphone.stamp(1_000 * ms, 980 * ms))
        // A real gap (an interruption): resync to the measured time.
        assertEquals(1_200 * ms, Microphone.stamp(1_000 * ms, 1_200 * ms))
        // Far early (a restart): resync too.
        assertEquals(700 * ms, Microphone.stamp(1_000 * ms, 700 * ms))
    }
}

/** The strip's motion model (checklist 89–93). */
class LiquidStripTest {
    private val strip = LiquidStrip().apply { rest(Vec(380.0, 400.0), animated = false) }

    private fun run(seconds: Double) {
        var t = 0.0
        while (t < seconds) {
            strip.tick(1.0 / 60); t += 1.0 / 60
        }
    }

    @Test
    fun aDropletStretchesIntoTheStripAndTheControlsFanOut() {
        strip.apply(StripMode.Recording(0, false))
        assertTrue(strip.isPresent)
        assertTrue(strip.geometry.body.height < 60)
        run(1.5)
        val g = strip.geometry
        assertTrue(g.controlsLive)
        assertEquals(StripMetrics.EXPANDED_HEIGHT, g.body.height, 0.5)
        assertEquals(StripMetrics.WIDTH, g.body.width, 0.5)
        assertTrue(g.rows.all { abs(it.opacity - 1) < 0.01 })
    }

    @Test
    fun sendingPoursIntoTheStatusCircleAndBudsItsBubbleLater() {
        strip.apply(StripMode.Recording(0, false)); run(1.5)
        strip.apply(StripMode.Sending(null))
        run(0.6)
        assertFalse(strip.geometry.controlsLive)
        assertEquals(StripMode.Phase.Sending, strip.statusIcon)
        assertEquals(StripMetrics.WIDTH, strip.geometry.body.height, 1.0)
        assertNull("Sending… waits 1.2 s", strip.bubble)
        run(1.0)
        assertEquals(LiquidStrip.Bubble.Sending(false), strip.bubble)
        strip.measuredBubble(120.0, 34.0, LiquidStrip.Bubble.Sending(false))
        run(1.0)
        val bubble = strip.geometry.bubble!!
        assertEquals(120.0, bubble.width, 1.0)
        // Beside the body's inner edge, `bubbleGap` away (strip on the right edge).
        assertEquals(strip.geometry.body.minX - StripMetrics.BUBBLE_GAP, bubble.maxX, 1.0)
    }

    @Test
    fun aFailedSendShakesAndOffersShare() {
        strip.apply(StripMode.Recording(0, false)); run(1.5)
        val restX = strip.geometry.body.midX
        strip.apply(StripMode.Failed(StripFailure.Unreachable))
        var maxShift = 0.0
        repeat(40) { strip.tick(1.0 / 60); maxShift = maxOf(maxShift, abs(strip.geometry.body.midX - restX)) }
        assertTrue("shook by $maxShift", maxShift > 5)
        run(1.5)
        assertEquals(restX, strip.geometry.body.midX, 0.5)
        assertEquals(LiquidStrip.Bubble.Failed(StripFailure.Unreachable), strip.bubble)
        strip.measuredBubble(200.0, 40.0, strip.bubble!!); run(1.0)
        assertTrue(strip.geometry.bubbleInteractive)
    }

    @Test
    fun aThrowStretchesTheStripAlongIt() {
        strip.apply(StripMode.Recording(0, false)); run(1.5)
        strip.drag(Vec(200.0, 400.0), Vec(-2600.0, 0.0))
        run(0.1)
        assertTrue("stretched sideways", strip.geometry.body.width > StripMetrics.WIDTH * 1.1)
        strip.release(Vec(0.0, 0.0)); strip.rest(Vec(30.0, 400.0), animated = true); run(2.0)
        assertEquals(StripMetrics.WIDTH, strip.geometry.body.width, 0.5)
    }

    @Test
    fun holdingTheDotShowsTheTimeAndNoticesWaitBehindIt() {
        strip.apply(StripMode.Recording(0, false)); run(1.5)
        strip.setNotice(StripNotice("Length limit", "Sending in 10 s")); run(0.5)
        assertEquals(LiquidStrip.Bubble.Notice(StripNotice("Length limit", "Sending in 10 s")), strip.bubble)
        strip.setHolding(true)
        assertEquals(LiquidStrip.Bubble.Elapsed, strip.bubble)
        strip.setHolding(false)
        assertTrue(strip.bubble is LiquidStrip.Bubble.Notice)
    }

    @Test
    fun leavingFadesTheGlassAwayAndDiscardShrinks() {
        strip.apply(StripMode.Recording(0, false)); run(1.5)
        strip.apply(StripMode.Discarded); run(1.0)
        assertEquals(LiquidStrip.Bubble.Discarded, strip.bubble)
        strip.apply(null); run(1.5)
        assertFalse(strip.isPresent)
        assertTrue(strip.geometry.alpha < 0.01)
        assertFalse(strip.isAnimating)
    }

    @Test
    fun reduceMotionCrossFadesInsteadOfMorphing() {
        strip.reduceMotion = true
        strip.apply(StripMode.Recording(0, false))
        run(0.3)
        // Two 0.11 s fades and the new state applied instantly in between.
        assertTrue(strip.geometry.rows.all { abs(it.opacity - 1) < 0.01 })
        assertEquals(StripMetrics.EXPANDED_HEIGHT, strip.geometry.body.height, 0.5)
    }

    @Test
    fun theOverlayLingersForTheExitToSettle() {
        // Measured with SwiftUI on macOS: Spring(duration: 0.425, bounce: 0).settlingDuration is
        // 0.7000 s, so iOS's overlayLinger is exactly 970 ms.
        assertEquals(0.7000, StripMotion.exitFade.settlingDuration, 0.0005)
        assertEquals(970L, StripMotion.overlayLingerMs)
    }
}

/** The H.264 encoder choice (checklist 39): hardware High profile wherever a device has it. */
class EncoderChoiceTest {
    private fun e(name: String, hw: Boolean, high: Boolean) = EncoderChoice.Encoder(name, hw, high)

    @Test
    fun hardwareHighProfileWins() {
        // A typical phone: Google's software encoder listed first, the SoC's hardware one after.
        val phone = listOf(e("c2.android.avc.encoder", false, false), e("c2.qti.avc.encoder", true, true))
        assertEquals("c2.qti.avc.encoder", EncoderChoice.pick(phone, 34)!!.name)
        // High anywhere beats hardware without it.
        assertEquals("sw.high", EncoderChoice.pick(listOf(e("hw.base", true, false), e("sw.high", false, true)), 34)!!.name)
        // Nothing with High (an emulator): the platform default.
        assertNull(EncoderChoice.pick(listOf(e("c2.android.avc.encoder", false, false), e("OMX.google.h264.encoder", false, false)), 34))
        // Android 9: Codec2 encoders were experimental; an OMX one is chosen.
        assertEquals("OMX.google.h264.encoder", EncoderChoice.pick(listOf(e("c2.android.avc.encoder", false, false), e("OMX.google.h264.encoder", false, false)), 28)!!.name)
        assertEquals("OMX.qcom.video.encoder.avc", EncoderChoice.pick(listOf(e("c2.x", true, true), e("OMX.google.h264.encoder", false, false), e("OMX.qcom.video.encoder.avc", true, true)), 28)!!.name)
        assertNull(EncoderChoice.pick(emptyList()))
    }
}

/** Reply bodies are read up to a cap on every Android version (checklist 101–104). */
class ReadUpToTest {
    @Test
    fun readsEverythingUnderTheCapAndStopsAtIt() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertEquals(20_000, java.io.ByteArrayInputStream(bytes).readUpTo(64 * 1024).size)
        val capped = java.io.ByteArrayInputStream(bytes).readUpTo(10_000)
        assertEquals(10_000, capped.size)
        assertEquals(bytes[9_999], capped.last())
    }
}


/** Coming back online retries the outbox (iOS `Reachability`), including after an offline launch. */
class ReachabilityTest {
    @Test
    fun onlyOnlineAfterOfflineCounts() {
        val online = Reachability()
        assertFalse("the first update isn't a return", online.update(true))
        assertFalse(online.update(true))
        assertFalse(online.update(false))
        assertTrue(online.update(true))
        // An offline launch is seeded as offline: the first network is a return.
        val offlineLaunch = Reachability().apply { update(false) }
        assertTrue(offlineLaunch.update(true))
    }
}

/** The reachability probe at each recording start (iOS `findMac`). */
class HealthProbeTest {
    private fun server(delayMs: Long, status: Int): java.net.ServerSocket {
        val socket = java.net.ServerSocket(0)
        Thread {
            runCatching {
                while (true) {
                    val c = socket.accept()
                    Thread {
                        runCatching {
                            Thread.sleep(delayMs)
                            c.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            c.close()
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true }.start()
        return socket
    }

    @Test
    fun theFirstToAnswerWinsWithoutWaitingForTheRest() = kotlinx.coroutines.runBlocking {
        val slow = server(3_000, 200)
        val fast = server(0, 200)
        val urls = listOf("http://127.0.0.1:${slow.localPort}", "http://127.0.0.1:${fast.localPort}")
        val t0 = System.nanoTime()
        val (reachable, revoked) = HealthProbe.probe(urls, "t")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(urls[1], reachable)
        assertFalse(revoked)
        assertTrue("didn't wait for the slow one: $ms ms", ms < 1_500)
        slow.close(); fast.close()
    }

    @Test
    fun nothingAnsweringIsNullAndPlainHttpRefusalsArentRevocations() = kotlinx.coroutines.runBlocking {
        val refusing = server(0, 401)
        val (reachable, revoked) = HealthProbe.probe(listOf("http://127.0.0.1:${refusing.localPort}", "http://127.0.0.1:1"), "t")
        assertNull(reachable)
        assertFalse("only https 401/403 means the link was revoked", revoked)
        refusing.close()
    }
}

/** Reply fields read as Swift's casts read them (IngestReply). */
class ReplyCastTest {
    private fun reply(json: String) = IngestReply.fromBody(json.toByteArray())

    @Test
    fun numbersBridgeToBoolOnlyAsZeroOrOne() {
        assertEquals(IngestReply.Queued("S"), reply("""{"delivered":0,"state":"queued","sessionTitle":"S"}"""))
        assertEquals(IngestReply.Delivered("S"), reply("""{"delivered":1,"sessionTitle":"S"}"""))
        assertEquals(IngestReply.Delivered("S"), reply("""{"delivered":2,"state":"queued","sessionTitle":"S"}"""))
    }

    @Test
    fun nonStringTitlesAndStatesAreIgnored() {
        assertEquals(IngestReply.Delivered("Scrawl"), reply("""{"delivered":true,"sessionTitle":42}"""))
        assertEquals(IngestReply.Saved, reply("""{"delivered":false,"state":7}"""))
    }
}
