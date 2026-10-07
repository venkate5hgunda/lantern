package dev.lantern.peer

import android.Manifest
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

@RunWith(AndroidJUnit4::class)
class BrowserInteropHarness {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @Test(timeout = 140_000) fun realCameraToDesktopBrowserOfferAnswer() {
        assumeTrue("Opt-in only: lantern.interop=1",
            InstrumentationRegistry.getArguments().getString("lantern.interop") == "1")
        check(PeerService.instance == null) { "Camera service already owns this device" }
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val offerFile = File(directory, "lantern-interop-offer.json")
        val answerFile = File(directory, "lantern-interop-answer.json")
        val stopFile = File(directory, "lantern-interop-stop")
        val camera = CameraRtc(InstrumentationRegistry.getInstrumentation().targetContext)
        val sourceFrames = AtomicLong()
        val sink = VideoSink { sourceFrames.incrementAndGet() }
        var sharedTrack: VideoTrack? = null
        var sessionId: String? = null
        var failure = "offer_invalid"
        try {
            answerFile.delete()
            stopFile.delete()
            check(offerFile.isFile && offerFile.length() in 1..96L * 1024) { "Offer file unavailable or oversized" }
            val offerBytes = FileInputStream(offerFile).use { source ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 96 * 1024) { "Offer exceeded limit while reading" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            val input = JSONObject(String(offerBytes, StandardCharsets.UTF_8))
            val offer = input.optJSONObject("offer") ?: error("Offer object missing")
            check(offer.optString("type") == "offer" && offer.opt("sdp") is String &&
                input.opt("audio") == false) { "Video-only offer required" }
            val sdp = offer.getString("sdp")
            check(sdp.isNotEmpty() && sdp.toByteArray(StandardCharsets.UTF_8).size <= 64 * 1024) {
                "Offer SDP exceeds limit"
            }
            check(Regex("(?m)^a=candidate:[^\\r\\n]*\\s(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\s+\\d+\\s+typ\\s+host\\b")
                .containsMatchIn(sdp)) { "Offer lacks a raw IPv4 host candidate" }
            offerFile.delete()
            failure = "capture_unavailable"
            check(camera.start()) { "Camera2 capturer unavailable" }
            sharedTrack = camera.preview() ?: error("Shared video track unavailable")
            sharedTrack.addSink(sink)
            val captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < captureDeadline &&
                (!camera.ready() || camera.frameJpeg() == null || sourceFrames.get() < 5L)) Thread.sleep(100)
            check(camera.ready() && camera.frameJpeg() != null) { "No actual camera frame" }
            failure = "video_track_unavailable"
            check(sourceFrames.get() >= 5) { "Shared WebRTC video track must receive multiple frames" }
            failure = "negotiation_failed"
            val id = UUID.randomUUID().toString()
            sessionId = id
            val answer = camera.answer(Slot(id, "interop-only", false,
                System.currentTimeMillis() + 30_000, System.nanoTime() / 1_000_000 + 30_000), sdp, false)
            val valid = answer as? CameraRtc.Negotiation.Success ?: error("Native WebRTC did not answer")
            val reply = JSONObject().put("sessionId", id)
                .put("answer", JSONObject().put("type", "answer").put("sdp", valid.sdp))
            FileOutputStream(answerFile).use {
                it.write(reply.toString().toByteArray(StandardCharsets.UTF_8))
                it.fd.sync()
            }
            failure = "stop_timeout"
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            var nextStats = 0L
            while (!stopFile.isFile && System.nanoTime() < deadline) {
                check(PeerService.instance == null) { "Camera service started during interop" }
                val now = System.nanoTime()
                if (now >= nextStats) {
                    camera.transportStats(id)?.let { stats ->
                        val status = JSONObject()
                            .put("iceState", stats.iceState)
                            .put("connectionState", stats.connectionState)
                            .put("sourceTrackFrames", sourceFrames.get())
                            .put("videoSenderBound", stats.videoSenderBound)
                            .put("videoDirection", stats.videoDirection)
                            .put("activeVideoEncodings", stats.activeVideoEncodings)
                            .put("videoCodec", stats.videoCodec)
                            .put("candidatePairs", stats.candidatePairs)
                            .put("requestsSent", stats.requestsSent)
                            .put("responsesReceived", stats.responsesReceived)
                            .put("requestsReceived", stats.requestsReceived)
                            .put("responsesSent", stats.responsesSent)
                            .put("outboundVideoBytes", stats.outboundVideoBytes)
                            .put("outboundFramesEncoded", stats.outboundFramesEncoded)
                        InstrumentationRegistry.getInstrumentation().sendStatus(0,
                            Bundle().apply { putString("lantern.transport", status.toString()) })
                    }
                    nextStats = now + TimeUnit.SECONDS.toNanos(5)
                }
                Thread.sleep(200)
            }
            check(stopFile.isFile) { "Desktop browser did not signal completion" }
        } catch (_: Exception) {
            FileOutputStream(answerFile).use {
                it.write(JSONObject().put("error", failure).toString().toByteArray(StandardCharsets.UTF_8))
                it.fd.sync()
            }
            val errorDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!stopFile.isFile && System.nanoTime() < errorDeadline) Thread.sleep(100)
            fail("Interop harness failed: $failure")
        } finally {
            sessionId?.let(camera::close)
            sharedTrack?.removeSink(sink)
            camera.destroy()
            offerFile.delete()
            answerFile.delete()
            stopFile.delete()
        }
    }
}
