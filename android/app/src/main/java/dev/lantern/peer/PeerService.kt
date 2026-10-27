package dev.lantern.peer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PeerService : Service() {
    companion object { @Volatile var instance: PeerService? = null; private set }
    private lateinit var state: PeerState
    private lateinit var doorbell: DoorbellStore
    @Volatile private var storageError: String? = null
    private lateinit var events: EventStore
    private lateinit var camera: CameraRtc
    private lateinit var pushes: PushStore
    private lateinit var backups: BackupStore
    private lateinit var backupApi: BackupApi
    private val backupPairing = PairingPolicy()
    private val backupWork = Executors.newSingleThreadExecutor()
    private val backupPairPending = java.util.concurrent.atomic.AtomicBoolean()
    private val backupPending = java.util.concurrent.atomic.AtomicBoolean()
    private val pushWork = Executors.newSingleThreadExecutor()
    private val pushPending = java.util.concurrent.atomic.AtomicBoolean()
    private val analysisWork = Executors.newSingleThreadExecutor()
    private val analysisPending = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var detector: PersonDetector? = null
    private val presence = PresencePolicy()
    @Volatile private var personAt = 0L
    @Volatile private var sampledAt: Long? = null
    @Volatile private var analysisError: String? = null
    @Volatile private var shuttingDown = false
    private var server: SecureServer? = null
    @Volatile private var stun: LocalStun? = null
    private val pairing = PairingPolicy()
    private val slots = Slots(onExpire = { camera.close(it) })
    private val work = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val snapshotGate = SnapshotGate()
    @Volatile private var foregroundReady = false
    @Volatile private var changingIdentity = false
    @Volatile private var currentHealth = "stopped"
    val health: String get() = when {
        currentHealth in listOf("running", "disarmed: HTTPS ready") && stun?.running != true ->
            "degraded: local STUN unavailable"
        currentHealth == "running" && !camera.ready() -> "degraded: capture unavailable"
        else -> currentHealth
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        state = PeerState(this)
        events = EventStore(this, state.id)
        camera = CameraRtc(this)
        doorbell = DoorbellStore(this)
        pushes = PushStore(this)
        backups = BackupStore(this)
        backupApi = BackupApi(backups, state, backupPairing)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("peer", "Lantern camera", NotificationManager.IMPORTANCE_LOW))
        val notice: Notification = NotificationCompat.Builder(this, "peer")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(state.name()).setContentText("Tap to open Lantern camera controls")
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(1, notice)
            foregroundReady = true
        } catch (_: Exception) {
            currentHealth = "degraded: foreground camera service unavailable"
            stopSelf()
            return
        }
        scheduler.scheduleWithFixedDelay({ slots.expire() }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ synchronizeBackups() }, 5, 5, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ deliverPushes() }, 5, 15, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({
            if (!shuttingDown && analysisPending.compareAndSet(false, true)) try {
                analysisWork.execute { try { analyzePeople() } finally { analysisPending.set(false) } }
            } catch (_: java.util.concurrent.RejectedExecutionException) { analysisPending.set(false) }
        }, 2, 2, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({
            if (!work.isShutdown) try { work.execute { maintainEvidence() } }
            catch (_: java.util.concurrent.RejectedExecutionException) { /* stopping */ }
        }, 60, 60, TimeUnit.SECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) { stopSelf(); return START_NOT_STICKY }
        work.execute {
            if (changingIdentity) return@execute
            if (!Identity.available()) { currentHealth = "not configured: import a PKCS#12 identity"; return@execute }
            try { events.pruneOrphans(); events.maintain() } catch (_: Exception) {
                storageError = "Event storage recovery failed. Check available storage."
            }
            if (server == null) {
                try { server = SecureServer(this, state.origins, ::handle).also { it.start() } }
                catch (_: Exception) { currentHealth = "degraded: HTTPS unavailable"; return@execute }
            }
            if (stun?.running != true) {
                try { stun = LocalStun().also { it.start() } }
                catch (_: Exception) {
                    stun?.stop()
                    stun = null
                    server?.stop()
                    server = null
                    currentHealth = "degraded: local STUN UDP 8444 unavailable"
                    return@execute
                }
            }
            if (state.armed()) {
                val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                currentHealth = if (!permitted) "degraded: camera or microphone permission missing"
                    else if (camera.start()) "running" else "degraded: capture unavailable"
            } else currentHealth = "disarmed: HTTPS ready"
        }
        return START_NOT_STICKY
    }
    private fun maintainEvidence() {
        try {
            events.maintain()
            if (state.automaticSnapshots() && state.armed() && camera.ready() && !changingIdentity) {
                when (snapshotGate.enter()) {
                    SnapshotAdmission.READY -> try {
                        val jpeg = camera.frameJpeg() ?: error("No current frame")
                        if (state.automaticSnapshots() && state.armed() && camera.ready() && !changingIdentity) events.add(jpeg, "scheduled")
                    } finally { snapshotGate.exit() }
                    else -> return
                }
            }
            storageError = null
        } catch (_: Exception) { storageError = "Automatic capture or storage maintenance failed. Check free space and camera health." }
    }
    private fun synchronizeBackups() {
        if (shuttingDown || !::backups.isInitialized || !backupPending.compareAndSet(false, true)) return
        try { backupWork.execute {
            try { PeerBackup(backups, events, state, { !shuttingDown }).sync() } catch (_: Exception) { /* persisted peer status and subsequent reconciliation recover */ }
            finally { backupPending.set(false) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { backupPending.set(false) }
    }
    fun newBackupCode(): String = backupPairing.generate()
    fun backupStatus(): String {
        val status = backups.status(events.list(200).size)
        val targets = status.getJSONArray("targets")
        return buildString {
            append("Local snapshots to protect: ${status.getInt("desired")}\n")
            append("Peer storage used: ${status.getLong("replicaBytes") / 1048576} MiB of ${status.getLong("quotaBytes") / 1048576} MiB\n\n")
            if (targets.length() == 0) append("No backup peer configured. Pair another camera to add redundancy.")
            for (index in 0 until targets.length()) {
                val target = targets.getJSONObject(index)
                append("${target.getString("origin")}\nVerified copies: ${target.getInt("verified")} / ${status.getInt("desired")}\n")
                append("Pending: ${target.getInt("pending")}\nLast reconciled: ${if (target.isNull("lastSync")) "Not yet" else target.getString("lastSync")}\n")
                if (!target.isNull("error")) append("${target.getString("error")}\n")
                append("\n")
            }
        }
    }
    fun backupTargets(): List<Pair<String, String>> = backups.targets().map { it.id to it.origin }
    fun unlinkBackup(id: String) { backups.unlink(id) }
    fun pairBackup(origin: String, pin: String, code: String, ownOrigin: String, callback: (String) -> Unit) {
        if (shuttingDown || !backupPairPending.compareAndSet(false, true)) {
            callback("Another backup pairing is in progress, or the service is stopping."); return
        }
        backupWork.execute {
            val result = try {
                PeerBackup(backups, events, state, { !shuttingDown }).pair(origin, pin, code, ownOrigin)
                "Cameras paired for bidirectional encrypted backup. Synchronization starts automatically."
            } catch (_: Exception) { "Pairing failed. Verify both addresses, certificate fingerprint, fresh code and available storage." }
            backupPairPending.set(false)
            android.os.Handler(mainLooper).post { callback(result) }
        }
    }
    private fun activeViewerIds(): Set<String> = state.grants.list().filter { it.expires > System.currentTimeMillis() }.map { it.id }.toSet()
    private fun publishEvent(id: String, type: String, created: Long) {
        try {
            pushes.enqueue(id, JSONObject().put("eventId", id).put("deviceId", state.id).put("cameraName", state.name())
                .put("type", type).put("createdAt", stamp(created)), activeViewerIds())
            deliverPushes()
        } catch (_: Exception) { /* The saved event remains available even if push storage fails. */ }
    }
    private fun deliverPushes() {
        if (shuttingDown || !::pushes.isInitialized || !pushPending.compareAndSet(false, true)) return
        try { pushWork.execute { try { PushDelivery(pushes) { state.grants.list() }.run() } finally { pushPending.set(false) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { pushPending.set(false) }
    }
    private fun analyzePeople() {
        if (!state.personDetection() || !state.armed() || !camera.ready() || changingIdentity || shuttingDown) {
            presence.reset(); personAt = 0
            detector?.close(); detector = null
            return
        }
        if (getSystemService(android.os.PowerManager::class.java).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
            presence.reset(); analysisError = "Person analysis paused due to thermal pressure"; return
        }
        try {
            val current = detector ?: PersonDetector(this).also { detector = it }
            val frame = camera.frameJpeg() ?: error("No fresh analysis frame")
            val scores = current.detect(frame)
            if (!state.personDetection() || !state.armed() || shuttingDown) { presence.reset(); return }
            sampledAt = System.currentTimeMillis()
            if (scores.isNotEmpty()) personAt = android.os.SystemClock.elapsedRealtime()
            val confidence = presence.observe(scores)
            analysisError = null
            if (confidence != null) {
                val event = events.add(frame, "person", confidence)
                publishEvent(event.id, "person", event.created)
            }
        } catch (_: Exception) { presence.reset(); analysisError = "Person analysis or evidence storage unavailable" }
    }
    fun personDetection(): Boolean = state.personDetection()
    fun personDetection(enabled: Boolean) { state.personDetection(enabled) }
    fun automaticSnapshots(): Boolean = state.automaticSnapshots()
    fun automaticSnapshots(enabled: Boolean) { state.automaticSnapshots(enabled) }
    fun ringDoorbell(callback: (String) -> Unit) {
        work.execute {
            val result = try {
                if (!state.armed() || !camera.ready()) "Start the camera before ringing."
                else {
                    val ring = doorbell.ring()
                    if (ring == null) "Wait five seconds before ringing again."
                    else { publishEvent(ring.id, "doorbell", ring.created); "Doorbell saved and queued for registered notification viewers." }
                }
            } catch (_: Exception) { "Doorbell could not be saved. Check storage." }
            android.os.Handler(mainLooper).post { callback(result) }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    fun lastSceneActivity(): Long = maxOf(camera.lastSignificantMotionAt, personAt)
    fun ambientDisplay(): Boolean = state.ambientDisplay()
    fun ambientDisplay(enabled: Boolean) { state.ambientDisplay(enabled) }
    fun preview(): org.webrtc.VideoTrack? = camera.preview()
    fun eglContext(): org.webrtc.EglBase.Context = camera.eglContext()
    fun newCode(): String = pairing.generate()
    internal fun grants(): List<Grant> = state.grants.list()
    fun approvedOrigins(): Set<String> = state.origins.list()
    fun updateSettings(name: String, approvedOrigins: Set<String>) {
        state.saveSettings(name, approvedOrigins)
    }
    fun pauseHttpsForIdentityChange(): Boolean = try {
        changingIdentity = true
        work.submit<Boolean> {
            stun?.stop()
            stun = null
            server?.stop()
            server = null
            slots.clear().forEach(camera::close)
            currentHealth = "degraded: HTTPS paused for identity change"
            true
        }.get(10, TimeUnit.SECONDS)
    } catch (_: Exception) { false }
    fun resumeHttpsAfterIdentityChange(): Boolean = try {
        work.submit<Boolean> {
            stun?.stop()
            stun = null
            server?.stop()
            server = null
            if (!Identity.available()) {
                currentHealth = "degraded: HTTPS identity unavailable"
                changingIdentity = false
                false
            } else try {
                server = SecureServer(this, state.origins, ::handle).also { it.start() }
                stun = LocalStun().also { it.start() }
                changingIdentity = false
                val captureStarted = camera.ready() || (state.armed() &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    camera.start())
                currentHealth = if (state.armed() && captureStarted) "running"
                    else if (state.armed()) "degraded: capture unavailable" else "disarmed: HTTPS ready"
                true
            } catch (_: Exception) {
                stun?.stop()
                stun = null
                server?.stop()
                server = null
                changingIdentity = false
                currentHealth = "degraded: HTTPS unavailable after identity change"
                false
            }
        }.get(10, TimeUnit.SECONDS)
    } catch (_: Exception) { false }
    fun revoke(id: String) {
        if (state.grants.revoke(id)) {
            state.saveGrants()
            pushes.remove(id)
            slots.removeOwner(id).forEach(camera::close)
        }
    }
    fun disarm() {
        state.armed(false)
        slots.clear().forEach(camera::close)
        work.execute { camera.stop(); currentHealth = "disarmed: HTTPS ready" }
    }
    override fun onDestroy() {
        shuttingDown = true
        state.armed(false)
        slots.clear().forEach(camera::close)
        stun?.stop()
        stun = null
        server?.stop()
        scheduler.shutdownNow()
        work.shutdownNow()
        analysisWork.shutdown()
        pushWork.shutdown()
        backupWork.shutdown()
        // Cleanup follows the actual termination of all owners; never dispose
        // native capture or close SQLite underneath an in-flight operation.
        val stoppedServer = server
        Thread {
            try { stoppedServer?.awaitStopped() } catch (_: InterruptedException) { return@Thread }
            for (executor in listOf(work, analysisWork, pushWork, backupWork)) {
                try { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { return@Thread }
            }
            detector?.close(); detector = null
            camera.destroy(); events.close(); doorbell.close(); pushes.close(); backups.close()
        }.apply { name = "lantern-cleanup"; start() }
        currentHealth = "stopped"
        instance = null
        super.onDestroy()
    }

    private fun json(status: Int, value: JSONObject): Response =
        Response(status, value.toString().toByteArray(StandardCharsets.UTF_8))
    private fun bad() = Response.error(400, "bad_request", "Malformed request")
    private fun parse(req: Request): JSONObject? = try {
        if (req.header("content-type")?.substringBefore(';')?.trim() != "application/json") null
        else JSONObject(StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(req.body)).toString())
    } catch (_: Exception) { null }
    private fun handle(req: Request): Response {
        val path = req.path
        if (shuttingDown) return Response.error(503, "stopping", "Camera is stopping")
        backupApi.handle(req)?.let { return it }
        if (req.method == "GET" && path == "/api/v1/info") {
            val capabilities = JSONObject().apply {
                put("video", camera.ready())
                put("audio", camera.ready())
                put("snapshots", camera.ready())
                put("personDetection", state.personDetection() && detector != null && analysisError == null)
                put("pushNotifications", true)
                put("analytics", true)
                put("peerBackup", true)
                put("doorbell", true)
                put("rollingSnapshots", true)
            }
            val info = JSONObject().put("apiVersion", 1).put("deviceId", state.id)
                .put("name", state.name()).put("armed", state.armed() && camera.ready())
                .put("capabilities", capabilities)
            if (stun?.running == true) info.put("localStunPort", StunCodec.PORT)
            return json(200, info)
        }
        if (req.method == "POST" && path == "/api/v1/pair") {
            val value = parse(req) ?: return bad()
            if (value.opt("code") !is String || value.opt("viewerName") !is String) return bad()
            val code = value.optString("code")
            val name = value.optString("viewerName").trim()
            if (!code.matches(Regex("[0-9]{6}")) || name.isEmpty() || name.length > 80 || name.any { it.isISOControl() })
                return bad()
            return when (pairing.redeem(code)) {
                PairResult.LIMITED -> Response.error(429, "pairing_limited", "Pairing attempt limit reached")
                PairResult.OK -> {
                    val (token, grant) = state.grants.issue(name)
                    try { state.saveGrants() } catch (_: Exception) {
                        state.grants.revoke(grant.id)
                        return Response.error(503, "storage_unavailable", "Grant could not be stored")
                    }
                    json(201, JSONObject().put("token", token).put("expiresAt", stamp(grant.expires)).put("deviceId", state.id))
                }
                else -> Response.error(401, "invalid_code", "Pairing code unavailable or invalid")
            }
        }
        if (!path.startsWith("/api/v1/")) {
            return if (req.method == "GET") server?.static(path)
                ?: Response.error(503, "viewer_unavailable", "Viewer assets not installed")
            else Response.error(404, "not_found", "Not found")
        }
        val owner = state.grants.authenticate(req.header("authorization"))
            ?: return Response.error(401, "unauthorized", "Missing or expired grant")
        if (path == "/api/v1/backup-status" && req.method == "GET") return json(200, backups.status(events.list(200).size))
        if (path == "/api/v1/replica-events" && req.method == "GET") return json(200, JSONObject().put("replicas", backups.history()))
        val replicaId = Regex("^/api/v1/replica-events/([0-9a-f]{64})/snapshot$").matchEntire(path)?.groupValues?.get(1)
        if (replicaId != null && req.method == "GET") {
            val image = backups.restore(replicaId) ?: return Response.error(404, "replica_unavailable", "Replica missing, expired or corrupt; synchronization will retry")
            return Response(200, image, "image/jpeg")
        }
        if (path == "/api/v1/notifications" && req.method == "GET") {
            return json(200, pushes.status(owner.id).put("publicKey", WebPushCrypto.encode(WebPushCrypto.publicBytes(pushes.keys().public))))
        }
        if (path == "/api/v1/notifications" && req.method == "POST") {
            val value = parse(req) ?: return bad()
            try { pushes.prune(activeViewerIds()); pushes.subscribe(owner.id, value) }
            catch (_: IllegalArgumentException) { return bad() }
            catch (_: org.json.JSONException) { return bad() }
            return Response(204)
        }
        if (path == "/api/v1/notifications" && req.method == "DELETE") {
            pushes.remove(owner.id); return Response(204)
        }
        if (path == "/api/v1/analytics" && req.method == "GET") {
            return json(200, presence.json().put("deviceId", state.id).put("enabled", state.personDetection())
                .put("available", state.personDetection() && state.armed() && detector != null && analysisError == null && sampledAt?.let { System.currentTimeMillis() - it in 0..10_000 } == true)
                .put("sampledAt", sampledAt?.let(::stamp) ?: JSONObject.NULL).put("error", analysisError ?: JSONObject.NULL)
                .put("model", "efficientdet-lite0-1").put("window", "current service run"))
        }
        if (path == "/api/v1/storage" && req.method == "GET") {
            val status = events.status().put("automaticSnapshots", state.automaticSnapshots())
                .put("error", storageError ?: JSONObject.NULL)
            return json(200, status)
        }
        if (path == "/api/v1/doorbell" && req.method == "GET") {
            return json(200, JSONObject().put("deviceId", state.id)
                .put("rings", JSONArray().also { array -> doorbell.list().forEach { array.put(doorbell.json(it)) } }))
        }
        val ringId = Regex("^/api/v1/doorbell/([0-9a-f-]{36})/ack$").matchEntire(path)?.groupValues?.get(1)
        if (ringId != null && req.method == "POST") {
            if (req.body.isNotEmpty()) return bad()
            return if (doorbell.acknowledge(ringId)) Response(204)
                else Response.error(404, "not_found", "Ring not found")
        }
        if (path == "/api/v1/sessions" && req.method == "POST") {
            if (!state.armed() || !camera.ready()) return Response.error(503, "capture_unavailable", "Capture unavailable")
            val value = parse(req) ?: return bad()
            val offer = value.optJSONObject("offer") ?: return bad()
            if (offer.opt("type") !is String || offer.opt("sdp") !is String) return bad()
            val sdp = offer.optString("sdp")
            if (sdp.toByteArray(StandardCharsets.UTF_8).size > 64 * 1024)
                return Response.error(413, "sdp_too_large", "Offer exceeds SDP limit")
            if (offer.optString("type") != "offer" || sdp.isEmpty() ||
                !value.has("audio") || value.opt("audio") !is Boolean) return bad()
            val audio = value.getBoolean("audio")
            val slot = slots.reserve(owner.id, audio)
                ?: return Response.error(409, "session_capacity", "Session or audio capacity reached")
            val answer = camera.answer(slot, sdp, audio)
            if (answer !is CameraRtc.Negotiation.Success) {
                slots.remove(slot.id, owner.id)
                camera.close(slot.id)
                return when (answer) {
                    CameraRtc.Negotiation.InvalidOffer -> bad()
                    CameraRtc.Negotiation.Timeout -> Response.error(504, "ice_timeout", "Negotiation timed out")
                    else -> Response.error(503, "rtc_unavailable", "Media negotiation unavailable")
                }
            }
            if (slots.owned(slot.id, owner.id) == null || !state.armed() || !camera.ready()) {
                camera.close(slot.id)
                return Response.error(503, "capture_unavailable", "Capture unavailable")
            }
            val delivery = SessionDelivery(slots, slot, camera::close)
            val body = JSONObject().put("sessionId", slot.id).put("expiresAt", stamp(slot.expires))
                .put("answer", JSONObject().put("type", "answer").put("sdp", answer.sdp))
            return Response(201, body.toString().toByteArray(StandardCharsets.UTF_8),
                onDeliveryFailure = delivery::failed)
        }
        val sessionId = Regex("^/api/v1/sessions/([0-9a-f-]{36})$").matchEntire(path)?.groupValues?.get(1)
        val iceSessionId = Regex("^/api/v1/sessions/([0-9a-f-]{36})/ice$").matchEntire(path)?.groupValues?.get(1)
        if (iceSessionId != null && req.method == "POST") {
            if (slots.owned(iceSessionId, owner.id) == null)
                return Response.error(404, "not_found", "Session not found")
            if (!state.armed() || !camera.ready())
                return Response.error(503, "capture_unavailable", "Capture unavailable")
            val body = parse(req) ?: return bad()
            if (!body.has("candidate") || !body.has("sdpMid") || !body.has("sdpMLineIndex")) return bad()
            val mid = body.opt("sdpMid").takeUnless { it == JSONObject.NULL }
            val input = IceInput.validate(body.opt("candidate"), mid, body.opt("sdpMLineIndex")) ?: return bad()
            return when (camera.addCandidate(iceSessionId, input)) {
                CameraRtc.IceResult.ADDED -> {
                    if (slots.owned(iceSessionId, owner.id) == null)
                        Response.error(404, "not_found", "Session not found")
                    else Response(204)
                }
                CameraRtc.IceResult.INVALID -> bad()
                CameraRtc.IceResult.UNAVAILABLE -> Response.error(503, "rtc_unavailable", "ICE candidate unavailable")
            }
        }
        if (sessionId != null && req.method == "PUT") {
            val slot = slots.heartbeat(sessionId, owner.id)
                ?: return Response.error(404, "not_found", "Session not found")
            return json(200, JSONObject().put("expiresAt", stamp(slot.expires)))
        }
        if (sessionId != null && req.method == "DELETE") {
            if (!slots.remove(sessionId, owner.id)) return Response.error(404, "not_found", "Session not found")
            camera.close(sessionId)
            return Response(204)
        }
        if (path == "/api/v1/events" && req.method == "GET") {
            val array = JSONArray()
            events.maintain()
            events.list().forEach { array.put(events.json(it)) }
            return json(200, JSONObject().put("events", array))
        }
        if (path == "/api/v1/events" && req.method == "POST") {
            if (req.body.isNotEmpty()) return bad()
            if (!state.armed() || !camera.ready()) return Response.error(503, "capture_unavailable", "Capture unavailable")
            when (snapshotGate.enter()) {
                SnapshotAdmission.BUSY -> return Response.error(409, "snapshot_busy", "Snapshot already in progress")
                SnapshotAdmission.RATE_LIMITED -> return Response.error(409, "snapshot_rate", "Wait before another snapshot")
                SnapshotAdmission.READY -> {}
            }
            try {
                val jpeg = camera.frameJpeg() ?: return Response.error(503, "frame_unavailable", "No current frame")
                val event = try { events.add(jpeg) } catch (_: Exception) {
                    return Response.error(503, "storage_unavailable", "Snapshot could not be stored")
                }
                return json(201, events.json(event))
            } finally { snapshotGate.exit() }
        }
        val eventId = Regex("^/api/v1/events/([0-9a-f-]{36})/snapshot$").matchEntire(path)?.groupValues?.get(1)
        if (eventId != null && req.method == "GET") {
            val jpeg = events.snapshot(eventId) ?: return Response.error(404, "not_found", "Snapshot not found")
            return Response(200, jpeg, "image/jpeg")
        }
        return Response.error(404, "not_found", "Not found")
    }
}
