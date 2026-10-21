package dev.lantern.peer

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.AudioManager
import android.os.SystemClock
import org.webrtc.*
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class CameraRtc(private val context: Context) {
    sealed interface Negotiation {
        data class Success(val sdp: String) : Negotiation
        data object InvalidOffer : Negotiation
        data object Timeout : Negotiation
        data object Unavailable : Negotiation
    }
    private val lock = Any()
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var video: VideoTrack? = null
    @Volatile private var captureAvailable = false
    private val peers = mutableMapOf<String, PeerConnection>()
    private var lastFrame: VideoFrame? = null
    private val audioResources = mutableMapOf<String, Pair<AudioTrack, AudioSource>>()
    private var priorAudioRoute: Pair<Int, Boolean>? = null
    private var frameAt = 0L
    private val motion = SignificantMotion()
    @Volatile var lastSignificantMotionAt: Long = 0; private set
    private fun sampleMotion(frame: VideoFrame.I420Buffer, at: Long) {
        val sample = ByteArray(32 * 24)
        val y = frame.dataY
        for (row in 0 until 24) for (column in 0 until 32) {
            sample[row * 32 + column] = y.get((row * frame.height / 24) * frame.strideY + column * frame.width / 32)
        }
        if (motion.observe(sample, at)) lastSignificantMotionAt = at
    }

    fun preview(): VideoTrack? = synchronized(lock) { video }
    fun eglContext(): EglBase.Context = egl.eglBaseContext
    fun ready(): Boolean = captureAvailable && synchronized(lock) { video != null }

    fun start(): Boolean {
        synchronized(lock) { if (video != null) return true }
        return try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val built = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (name == null) { built.dispose(); return false }
            val camera = enumerator.createCapturer(name, null)
            if (camera == null) { built.dispose(); return false }
            val source = built.createVideoSource(false)
            val texture = SurfaceTextureHelper.create("lantern-capture", egl.eglBaseContext)
            synchronized(lock) { factory = built; capturer = camera; helper = texture; videoSource = source }
            camera.initialize(texture, context, object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) {
                    captureAvailable = success
                    source.capturerObserver.onCapturerStarted(success)
                }
                override fun onCapturerStopped() {
                    captureAvailable = false
                    source.capturerObserver.onCapturerStopped()
                }
                override fun onFrameCaptured(frame: VideoFrame) {
                    source.capturerObserver.onFrameCaptured(frame)
                    val now = SystemClock.elapsedRealtime()
                    if (synchronized(lock) { lastFrame == null || now - frameAt >= 1_000 }) {
                        val copy = frame.buffer.toI420()
                        if (copy != null) sampleMotion(copy, now)
                        synchronized(lock) {
                            lastFrame?.release()
                            lastFrame = VideoFrame(copy, frame.rotation, frame.timestampNs)
                            frameAt = now
                        }
                    }
                }
            })
            camera.startCapture(1280, 720, 20)
            val track = built.createVideoTrack("lantern-camera", source)
            synchronized(lock) { video = track }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun frameJpeg(): ByteArray? {
        val frame = synchronized(lock) {
            if (SystemClock.elapsedRealtime() - frameAt > 2_000) null else lastFrame?.also { it.retain() }
        } ?: return null
        try {
            val i420 = frame.buffer.toI420() ?: return null
            try {
                val width = i420.width
                val height = i420.height
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null
                val nv21 = ByteArray(width * height * 3 / 2)
                val y = i420.dataY
                val u = i420.dataU
                val v = i420.dataV
                for (row in 0 until height) for (col in 0 until width)
                    nv21[row * width + col] = y.get(row * i420.strideY + col)
                for (row in 0 until height / 2) for (col in 0 until width / 2) {
                    val pos = width * height + row * width + col * 2
                    nv21[pos] = v.get(row * i420.strideV + col)
                    nv21[pos + 1] = u.get(row * i420.strideU + col)
                }
                val stream = ByteArrayOutputStream()
                if (!YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        .compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, stream)) return null
                val jpeg = stream.toByteArray()
                if (frame.rotation == 0) return jpeg
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
                val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    android.graphics.Matrix().apply { postRotate(frame.rotation.toFloat()) }, true)
                return try {
                    ByteArrayOutputStream().use { output ->
                        if (!rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)) null else output.toByteArray()
                    }
                } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
            } finally { i420.release() }
        } finally { frame.release() }
    }

    fun answer(slot: Slot, offer: String, audio: Boolean): Negotiation {
        val rtcFactory = synchronized(lock) { factory } ?: return Negotiation.Unavailable
        val track = synchronized(lock) { video } ?: return Negotiation.Unavailable
        val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val gather = CountDownLatch(1)
        val peer = rtcFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gather.countDown()
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {
                if (audio && synchronized(lock) { audioResources.containsKey(slot.id) }) {
                    (transceiver.receiver.track() as? AudioTrack)?.setEnabled(true)
                    speaker()
                }
            }
        }) ?: return Negotiation.Unavailable
        synchronized(lock) { peers[slot.id] = peer }
        var completed = false
        try {
            val remote = SessionDescription(SessionDescription.Type.OFFER, offer)
            when (set(peer, remote, false)) {
                SetResult.FAILURE -> return Negotiation.InvalidOffer
                SetResult.TIMEOUT -> return Negotiation.Timeout
                SetResult.SUCCESS -> {}
            }
            val videoTransceiver = peer.transceivers.firstOrNull {
                !it.isStopped && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            } ?: return Negotiation.InvalidOffer
            if (!videoTransceiver.sender.setTrack(track, false) ||
                !videoTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY) ||
                videoTransceiver.sender.track()?.id() != track.id()) return Negotiation.Unavailable
            if (audio) {
                val audioSource = rtcFactory.createAudioSource(MediaConstraints())
                val mic = rtcFactory.createAudioTrack("lantern-microphone-${slot.id}", audioSource)
                synchronized(lock) { audioResources[slot.id] = mic to audioSource }
                peer.addTrack(mic, listOf("lantern"))
                speaker()
            }
            val created = CountDownLatch(1)
            val result = AtomicReference<SessionDescription?>()
            peer.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { result.set(sdp); created.countDown() }
                override fun onCreateFailure(error: String) { created.countDown() }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints())
            if (!created.await(3, TimeUnit.SECONDS)) return Negotiation.Timeout
            val answer = result.get() ?: return Negotiation.InvalidOffer
            when (set(peer, answer, true)) {
                SetResult.FAILURE -> return Negotiation.Unavailable
                SetResult.TIMEOUT -> return Negotiation.Timeout
                SetResult.SUCCESS -> {}
            }
            if (!gather.await(8, TimeUnit.SECONDS)) return Negotiation.Timeout
            val sdp = peer.localDescription?.description ?: return Negotiation.Unavailable
            if (sdp.toByteArray(StandardCharsets.UTF_8).size > 64 * 1024) return Negotiation.Unavailable
            completed = true
            return Negotiation.Success(sdp)
        } catch (_: Exception) {
            return Negotiation.Unavailable
        } finally {
            if (!completed) close(slot.id)
        }
    }

    private enum class SetResult { SUCCESS, FAILURE, TIMEOUT }
    private fun set(peer: PeerConnection, sdp: SessionDescription, local: Boolean): SetResult {
        val latch = CountDownLatch(1)
        val success = java.util.concurrent.atomic.AtomicBoolean(false)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
            override fun onSetSuccess() { success.set(true); latch.countDown() }
            override fun onSetFailure(error: String) { latch.countDown() }
        }
        if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp)
        return when {
            !latch.await(3, TimeUnit.SECONDS) -> SetResult.TIMEOUT
            success.get() -> SetResult.SUCCESS
            else -> SetResult.FAILURE
        }
    }

    enum class IceResult { ADDED, INVALID, UNAVAILABLE }
    fun addCandidate(id: String, input: IceInput): IceResult {
        val peer = synchronized(lock) { peers[id] } ?: return IceResult.UNAVAILABLE
        val complete = CountDownLatch(1)
        val result = AtomicReference(IceResult.UNAVAILABLE)
        try {
            peer.addIceCandidate(IceCandidate(input.sdpMid, input.sdpMLineIndex, input.candidate),
                object : AddIceObserver {
                    override fun onAddSuccess() { result.set(IceResult.ADDED); complete.countDown() }
                    override fun onAddFailure(error: String) { result.set(IceResult.INVALID); complete.countDown() }
                })
            if (!complete.await(3, TimeUnit.SECONDS)) return IceResult.UNAVAILABLE
            return result.get()
        } catch (_: Exception) { return IceResult.UNAVAILABLE }
    }

    data class TransportStats(
        val iceState: String,
        val connectionState: String,
        val videoSenderBound: Boolean,
        val videoDirection: String,
        val activeVideoEncodings: Int,
        val videoCodec: String,
        val candidatePairs: Int,
        val requestsSent: Long,
        val responsesReceived: Long,
        val requestsReceived: Long,
        val responsesSent: Long,
        val outboundVideoBytes: Long,
        val outboundFramesEncoded: Long
    )

    fun transportStats(id: String): TransportStats? {
        val peer = synchronized(lock) { peers[id] } ?: return null
        val ready = CountDownLatch(1)
        val report = AtomicReference<RTCStatsReport?>()
        try {
            peer.getStats { stats -> report.set(stats); ready.countDown() }
            if (!ready.await(2, TimeUnit.SECONDS)) return null
            val all = report.get()?.statsMap?.values ?: return null
            val pairs = all.filter { it.type == "candidate-pair" }
            fun total(key: String) = pairs.sumOf { (it.members[key] as? Number)?.toLong() ?: 0L }
            val video = all.filter {
                it.type == "outbound-rtp" &&
                    (it.members["kind"] == "video" || it.members["mediaType"] == "video")
            }
            val sender = peer.transceivers.firstOrNull {
                !it.isStopped && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            }
            val codecId = video.firstNotNullOfOrNull { it.members["codecId"] as? String }
            val codec = (codecId?.let { report.get()?.statsMap?.get(it) }
                ?.members?.get("mimeType") as? String)?.take(32) ?: "unavailable"
            return TransportStats(peer.iceConnectionState().name, peer.connectionState().name,
                sender?.sender?.track()?.id() == synchronized(lock) { this.video?.id() },
                sender?.currentDirection?.name ?: "unavailable",
                sender?.sender?.parameters?.encodings?.count { it.active } ?: 0,
                codec,
                pairs.size, total("requestsSent"), total("responsesReceived"),
                total("requestsReceived"), total("responsesSent"),
                video.sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0L },
                video.sumOf { (it.members["framesEncoded"] as? Number)?.toLong() ?: 0L })
        } catch (_: Exception) { return null }
    }

    fun close(id: String) {
        val (peer, audio) = synchronized(lock) { peers.remove(id) to audioResources.remove(id) }
        if (peer == null) return
        try {
            peer.receivers.forEach { (it.track() as? AudioTrack)?.setEnabled(false) }
            peer.close()
            peer.dispose()
            audio?.first?.dispose()
            audio?.second?.dispose()
        } finally {
            synchronized(lock) {
                if (audioResources.isEmpty()) priorAudioRoute?.let { (mode, speaker) ->
                    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    manager.isSpeakerphoneOn = speaker
                    manager.mode = mode
                    priorAudioRoute = null
                }
            }
        }
    }

    private fun speaker() = synchronized(lock) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (priorAudioRoute == null) priorAudioRoute = manager.mode to manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.isSpeakerphoneOn = true
    }

    fun stop() {
        captureAvailable = false
        synchronized(lock) { peers.keys.toList() }.forEach(::close)
        val previous = synchronized(lock) {
            val held = listOf(capturer, helper, video, videoSource, factory)
            capturer = null; helper = null; video = null; videoSource = null; factory = null
            held
        }
        try { (previous[0] as? CameraVideoCapturer)?.stopCapture() } catch (_: Exception) {}
        synchronized(lock) { lastFrame?.release(); lastFrame = null }
        (previous[0] as? CameraVideoCapturer)?.dispose()
        (previous[1] as? SurfaceTextureHelper)?.dispose()
        (previous[2] as? VideoTrack)?.dispose()
        (previous[3] as? VideoSource)?.dispose()
        (previous[4] as? PeerConnectionFactory)?.dispose()
    }
    fun destroy() {
        stop()
        egl.release()
    }
}
