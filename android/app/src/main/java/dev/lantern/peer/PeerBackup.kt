package dev.lantern.peer

import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Explicit leaf-certificate pin plus normal HTTPS hostname verification. */
internal class BackupHttp(private val origin: String, pin: String, private val token: String? = null) {
    private val socketFactory: javax.net.ssl.SSLSocketFactory
    init {
        require(Origins.canonical(origin) == origin && pin.matches(Regex("[0-9a-f]{64}")))
        val expected = pin.hexBytes()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException("Client certificates unsupported") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No peer certificate")
                leaf.checkValidity()
                if (!MessageDigest.isEqual(expected, java.security.MessageDigest.getInstance("SHA-256").digest(leaf.encoded))) throw java.security.cert.CertificateException("Peer certificate changed")
            }
        }
        socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }.socketFactory
    }
    fun request(path: String, body: JSONObject? = null): JSONObject {
        require(path.matches(Regex("/api/v1/(info|backup/[a-z0-9/-]+)")))
        val connection = URL(origin + path).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = socketFactory
            // The default hostname verifier remains enabled; a pin is not a hostname.
            connection.instanceFollowRedirects = false; connection.connectTimeout = 5_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Cache-Control", "no-store")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray()
                require(bytes.size <= 96 * 1024)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            check(connection.responseCode in 200..299) { "Peer rejected backup request" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 1024 * 1024); output.write(buffer, 0, count) }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
internal class PeerBackup(
    private val store: BackupStore,
    private val events: EventStore,
    private val state: PeerState,
    private val alive: () -> Boolean,
    private val transport: (BackupTarget) -> (String, JSONObject?) -> JSONObject = { target ->
        val http = BackupHttp(target.origin, target.pin, target.token)
        val call: (String, JSONObject?) -> JSONObject = { path, body -> http.request(path, body) }
        call
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    fun sync() {
        store.maintain()
        for (target in store.targets()) {
            if (!alive() || Thread.currentThread().isInterrupted) return
            if (target.nextAttempt > now()) continue
            try {
                val request = transport(target)
                val inventory = request("/api/v1/backup/inventory", null).getJSONArray("replicas")
                val remote = (0 until inventory.length()).associate {
                    val item = inventory.getJSONObject(it); item.getString("eventId") to item.getString("sha256")
                }.toMutableMap()
                val local = events.list(200)
                val pending = store.pendingUpload(target.id)
                if (pending != null && (pending.eventId in remote || local.none { it.id == pending.eventId })) store.clearUpload(target.id)
                val ordered = local.sortedBy { if (it.id == pending?.eventId) 0 else 1 }
                var uploaded = 0
                var verified = 0
                val expected = mutableMapOf<String, String>()
                for (event in ordered) {
                    if (!alive() || Thread.currentThread().isInterrupted) return
                    val plain = events.snapshot(event.id) ?: continue
                    val hash = mediaHash(plain)
                    expected[event.id] = hash
                    if (remote[event.id] == hash) { verified++; continue }
                    if (uploaded >= 4) continue
                    val manifest = ReplicaManifest(state.id, event.id, event.created, event.trigger, event.confidence, plain.size, hash)
                    val bytes = store.uploadBytes(target, manifest, plain)
                    var offset = request("/api/v1/backup/begin", manifest.json()).getInt("received")
                    require(offset in 0..bytes.size)
                    while (offset < bytes.size) {
                        if (!alive() || Thread.currentThread().isInterrupted) return
                        val end = minOf(bytes.size, offset + 32_768)
                        val response = request("/api/v1/backup/${manifest.id}/chunk", JSONObject().put("offset", offset).put("data", WebPushCrypto.encode(bytes.copyOfRange(offset, end))))
                        val received = response.getInt("received"); require(received >= end && received <= bytes.size); offset = received
                    }
                    val receipt = request("/api/v1/backup/${manifest.id}/commit", JSONObject())
                    check(receipt.getString("sha256") == hash) { "Peer did not verify durable evidence" }
                    store.clearUpload(target.id); remote[event.id] = hash; uploaded++; verified++
                }
                if (uploaded > 0) {
                    // Admission may rotate old copies. Count the receiver's final
                    // inventory rather than receipts for objects since evicted.
                    val finalInventory = request("/api/v1/backup/inventory", null).getJSONArray("replicas")
                    verified = (0 until finalInventory.length()).count {
                        val item = finalInventory.getJSONObject(it)
                        expected[item.getString("eventId")] == item.getString("sha256")
                    }
                }
                store.synced(target.id, verified)
            } catch (_: Exception) { store.failed(target) }
        }
    }
    fun pair(origin: String, pin: String, code: String, ownOrigin: String) {
        require(code.matches(Regex("[0-9]{6}")) && ownOrigin in Identity.origins())
        val http = BackupHttp(origin, pin)
        val info = http.request("/api/v1/info")
        val receiver = info.getString("deviceId")
        require(receiver != state.id)
        check(store.targets().let { targets -> targets.size < 4 || targets.any { it.id == receiver } })
        val reverse = store.issue(receiver)
        val response = http.request("/api/v1/backup/pair", JSONObject().put("code", code).put("sourceDeviceId", state.id)
            .put("reverse", JSONObject().put("origin", ownOrigin).put("pin", Identity.fingerprint()).put("token", reverse.first).put("key", WebPushCrypto.encode(reverse.second))))
        check(response.getString("deviceId") == receiver)
        store.target(receiver, origin, pin, response.getString("token"), WebPushCrypto.decode(response.getString("key")))
    }
}
internal class BackupApi(private val store: BackupStore, private val state: PeerState, private val pairing: PairingPolicy) {
    private fun json(value: JSONObject, status: Int = 200) = Response(status, value.toString().toByteArray())
    private fun body(req: Request): JSONObject {
        require(req.header("content-type")?.substringBefore(';') == "application/json")
        return JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(req.body)).toString())
    }
    fun handle(req: Request): Response? {
        if (!req.path.startsWith("/api/v1/backup/")) return null
        try {
            if (req.path == "/api/v1/backup/pair" && req.method == "POST") {
                val value = body(req)
                val source = value.getString("sourceDeviceId")
                require(java.util.UUID.fromString(source).toString() == source && source != state.id && value.getString("code").matches(Regex("[0-9]{6}")))
                val reverse = value.getJSONObject("reverse")
                val origin = reverse.getString("origin"); val pin = reverse.getString("pin"); val token = reverse.getString("token"); val key = WebPushCrypto.decode(reverse.getString("key"))
                require(Origins.canonical(origin) == origin && pin.matches(Regex("[0-9a-f]{64}")) && token.matches(Regex("[A-Za-z0-9_-]{43}")) && key.size == 32)
                when (pairing.redeem(value.getString("code"))) {
                    PairResult.OK -> {}
                    PairResult.LIMITED -> return Response.error(429, "pairing_limited", "Backup pairing attempt limit reached")
                    else -> return Response.error(401, "invalid_code", "Backup pairing code expired or invalid")
                }
                val credentials = store.issue(source)
                store.target(source, origin, pin, token, key)
                return json(JSONObject().put("deviceId", state.id).put("token", credentials.first).put("key", WebPushCrypto.encode(credentials.second)), 201)
            }
            val grant = store.authenticate(req.header("authorization")) ?: return Response.error(401, "backup_unauthorized", "Missing or revoked backup grant")
            if (req.path == "/api/v1/backup/inventory" && req.method == "GET") return json(JSONObject().put("replicas", store.inventory(grant.source)))
            if (req.path == "/api/v1/backup/begin" && req.method == "POST") {
                val manifest = ReplicaManifest.parse(body(req))
                return json(JSONObject().put("received", store.begin(manifest, grant.source)))
            }
            val match = Regex("^/api/v1/backup/([0-9a-f]{64})/(chunk|commit)$").matchEntire(req.path)
            if (match != null && req.method == "POST") {
                val id = match.groupValues[1]
                if (match.groupValues[2] == "commit") return json(JSONObject().put("sha256", store.commit(id, grant)))
                val value = body(req); val data = value.getString("data"); require(data.length <= 43_691)
                return json(JSONObject().put("received", store.chunk(id, grant.source, value.getInt("offset"), WebPushCrypto.decode(data))))
            }
            return Response.error(404, "not_found", "Backup route not found")
        } catch (_: IllegalArgumentException) { return Response.error(400, "invalid_backup", "Invalid backup request") }
        catch (_: org.json.JSONException) { return Response.error(400, "invalid_backup", "Invalid backup request") }
        catch (_: Exception) { return Response.error(503, "backup_unavailable", "Backup storage unavailable or transfer incomplete") }
    }
}
