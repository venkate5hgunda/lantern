package dev.lantern.peer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.Signature
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import java.security.cert.X509Certificate
import org.json.JSONObject
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @Test fun sharedCameraDeliversCurrentJpegAndPersistentSnapshot() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val camera = CameraRtc(context)
            val delivered = AtomicInteger()
            val sink = VideoSink { delivered.incrementAndGet() }
            try {
                assertTrue("Camera2 capturer could not start", camera.start())
                val track = camera.preview()
                assertNotNull("Camera track was not created", track)
                track!!.addSink(sink)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var image: ByteArray? = null
                while (System.nanoTime() < deadline && (image == null || delivered.get() < 5)) {
                    if (camera.ready()) image = camera.frameJpeg()
                    if (image == null || delivered.get() < 5) Thread.sleep(200)
                }
                assertNotNull("Camera2 provided no recent frame within 20 seconds", image)
                assertTrue("Shared WebRTC VideoTrack must receive multiple frames", delivered.get() >= 5)
                assertNull(camera.transportStats("00000000-0000-0000-0000-000000000000"))
                val store = EventStore(context, "device-integration")
                try {
                    val event = store.add(image!!)
                    assertArrayEquals(image, store.snapshot(event.id))
                    assertTrue(store.list().any { it.id == event.id })
                } finally { store.close() }
                track.removeSink(sink)
                camera.stop()
                assertFalse("Stopped capture must report unavailable", camera.ready())
                assertNull("Stopped camera must not return a previous frame", camera.frameJpeg())
            } finally {
                camera.preview()?.removeSink(sink)
                camera.destroy()
            }
        }
    }

    @Test fun realDirectorySyncAndSnapshotRetentionWorkOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = ByteArrayOutputStream().use { output ->
            assertTrue(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray()
        }
        val store = EventStore(context, "device-integration")
        try {
            val event = store.add(image)
            assertArrayEquals(image, EventStore(context, "device-integration").use { it.snapshot(event.id) })
            assertNull(store.snapshot("../peer"))
        } finally { store.close() }
    }

    @Test fun localStunAnswersActualLoopbackDatagramAndReleasesOwnedPort() {
        assumeTrue("Will not disturb a running camera service", PeerService.instance == null)
        val stun = LocalStun()
        try {
            stun.start()
            assertTrue(stun.running)
            DatagramSocket().use { client ->
                client.soTimeout = 3_000
                val request = ByteArray(20).apply {
                    this[1] = 1
                    this[4] = 0x21
                    this[5] = 0x12
                    this[6] = 0xa4.toByte()
                    this[7] = 0x42
                    (8..19).forEach { this[it] = it.toByte() }
                }
                val loopback = InetAddress.getByName("127.0.0.1")
                client.send(DatagramPacket(request, request.size, loopback, StunCodec.PORT))
                val received = DatagramPacket(ByteArray(64), 64)
                client.receive(received)
                assertEquals(40, received.length)
                assertEquals(0x01, received.data[0].toInt())
                assertEquals(0x01, received.data[1].toInt())
                assertEquals(1, received.data[25].toInt())
                val maskedPort = ((received.data[26].toInt() and 255) shl 8) or
                    (received.data[27].toInt() and 255)
                assertEquals(client.localPort, maskedPort xor 0x2112)
                assertArrayEquals(request.copyOfRange(8, 20), received.data.copyOfRange(8, 20))
            }
        } finally { stun.stop() }
        assertFalse(stun.running)
        repeat(3) {
            stun.start()
            assertTrue(stun.running)
            stun.stop()
            assertFalse(stun.running)
        }
        DatagramSocket(StunCodec.PORT).use { assertFalse(it.isClosed) }
    }

    @Test fun inMemoryPkcs12ImportsAndSignsLocalHttpsHandshake() {
        assumeFalse("Will not replace an owner's installed identity", Identity.available())
        createTestIdentity().use { fixture ->
            try {
                val ownOrigins = Identity.import(fixture.bytes.copyOf(), fixture.password.copyOf())
                assertEquals(setOf("https://127.0.0.1:8443"), ownOrigins)
                assertTrue(Identity.available())
                assertImportedRsaSignature(fixture.leaf)
                assertTlsHandshake(fixture.root)
                assertRealHttpsApi(fixture.root)
            } finally { deleteTestIdentity() }
        }
    }

    @Test fun optionalOpenSslProvisioningProfileImportsAndServesHttps() {
        val args = InstrumentationRegistry.getArguments()
        val filename = args.getString("lantern.testP12File")
        val suppliedPassword = args.getString("lantern.testP12Password")
        assumeTrue("Supply a disposable scripts/provision.py test P12 and password through runner arguments",
            filename != null && suppliedPassword != null)
        assumeFalse("Will not replace an owner's installed identity", Identity.available())
        require(filename!!.matches(Regex("[A-Za-z0-9_.-]{1,64}\\.p12")) && !filename.startsWith('.'))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, filename)
        require(file.isFile && file.length() in 1..262_144) { "Test-only P12 missing or too large in app private files" }
        val password = suppliedPassword!!.toCharArray()
        val bytes = file.readBytes()
        try {
            val ownOrigins = Identity.import(bytes, password)
            assertTrue("Provision test fixture with --san 127.0.0.1", "https://127.0.0.1:8443" in ownOrigins)
            val installed = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val chain = installed.getCertificateChain("lantern-server").map { it as X509Certificate }
            assertTrue("OpenSSL fixture must contain leaf and household-test CA", chain.size >= 2)
            assertImportedRsaSignature(chain.first())
            assertTlsHandshake(chain.last())
        } finally {
            deleteTestIdentity()
            password.fill('\u0000')
            bytes.fill(0)
            file.delete()
        }
    }

    private fun deleteTestIdentity() {
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry("lantern-server")
    }

    private fun assertImportedRsaSignature(leaf: X509Certificate) {
        val installed = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val signer = Signature.getInstance("SHA256withRSA").also {
            it.initSign(installed.getKey("lantern-server", null) as java.security.PrivateKey)
            it.update("lantern-tls-key-test".toByteArray())
        }
        val signed = signer.sign()
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(leaf.publicKey)
            update("lantern-tls-key-test".toByteArray())
            verify(signed)
        })
    }

    private fun assertTlsHandshake(trustedRoot: X509Certificate) {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).also {
            it.load(null)
            it.setCertificateEntry("temporary-test-ca", trustedRoot)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trust)
        val clientContext = SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) }
        val server = Identity.context().serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.soTimeout = 10_000
        val workers = Executors.newSingleThreadExecutor()
        try {
            val handshake = workers.submit<Throwable?> {
                try {
                    (server.accept() as SSLSocket).use {
                        it.soTimeout = 10_000
                        it.startHandshake()
                    }
                    null
                } catch (error: Throwable) { error }
            }
            var clientFailure: Throwable? = null
            try {
                (clientContext.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use {
                    val parameters = it.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    it.sslParameters = parameters
                    it.soTimeout = 10_000
                    it.startHandshake()
                    assertTrue(it.session.isValid)
                }
            } catch (error: Throwable) { clientFailure = error }
            val serverFailure = handshake.get(10, TimeUnit.SECONDS)
            if (clientFailure != null) {
                val failure = AssertionError("Client TLS handshake failed; server failure: " +
                    "${serverFailure?.javaClass?.simpleName}: ${serverFailure?.message}", clientFailure)
                if (serverFailure != null) failure.addSuppressed(serverFailure)
                throw failure
            }
            if (serverFailure != null) throw AssertionError("Server TLS handshake failed", serverFailure)
        } finally { server.close(); workers.shutdownNow() }
    }

    private data class ApiReply(val status: Int, val json: JSONObject?, val headers: String)

    private fun trustedClient(root: X509Certificate): SSLContext {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).also {
            it.load(null)
            it.setCertificateEntry("test-household-ca", root)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trust)
        return SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) }
    }

    private fun api(
        tls: SSLContext, method: String, path: String, origin: String? = null,
        bearer: String? = null, body: String? = null, preflight: Boolean = false
    ): ApiReply {
        (tls.socketFactory.createSocket("127.0.0.1", 8443) as SSLSocket).use { socket ->
            socket.soTimeout = 10_000
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.startHandshake()
            val payload = body?.toByteArray(StandardCharsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:8443\r\nConnection: close\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (bearer != null) append("Authorization: Bearer $bearer\r\n")
                if (preflight) append("Access-Control-Request-Method: POST\r\n")
                if (payload != null) append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
                append("\r\n")
            }
            socket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
            if (payload != null) socket.outputStream.write(payload)
            socket.outputStream.flush()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = socket.inputStream.read(buffer)
                if (size < 0) break
                assertTrue("HTTP test response exceeded 1 MiB", output.size() + size <= 1_048_576)
                output.write(buffer, 0, size)
            }
            val response = output.toString(StandardCharsets.UTF_8.name())
            val boundary = response.indexOf("\r\n\r\n")
            assertTrue("HTTP response missing headers", boundary >= 0)
            val headers = response.substring(0, boundary)
            val status = headers.substringBefore("\r\n").split(' ')[1].toInt()
            val contents = response.substring(boundary + 4)
            return ApiReply(status, contents.takeIf(String::isNotEmpty)?.let(::JSONObject), headers)
        }
    }

    private fun expectError(reply: ApiReply, status: Int) {
        assertEquals(status, reply.status)
        assertTrue(reply.json?.optJSONObject("error")?.optString("code")?.isNotEmpty() == true)
    }

    private fun awaitHealth(expected: String): PeerService {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val service = PeerService.instance
            if (service?.health == expected) return service
            Thread.sleep(100)
        }
        fail("Peer service did not reach $expected; actual ${PeerService.instance?.health}")
        error("unreachable")
    }

    private fun assertRealHttpsApi(root: X509Certificate) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = PeerState(context)
        val oldOrigins = state.origins.list()
        val oldName = state.name()
        val oldArmed = state.armed()
        val tls = trustedClient(root)
        val issued = mutableListOf<String>()
        val origin = "https://127.0.0.1:8443"
        state.armed(false)
        state.saveOrigins(oldOrigins + origin + "https://approved.example")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    ContextCompat.startForegroundService(it, Intent(it, PeerService::class.java))
                }
                val service = awaitHealth("disarmed: HTTPS ready")
                val info = api(tls, "GET", "/api/v1/info")
                assertEquals(200, info.status)
                assertEquals(state.id, info.json!!.getString("deviceId"))
                assertEquals(StunCodec.PORT, info.json.getInt("localStunPort"))
                assertFalse(info.json.getBoolean("armed"))
                assertEquals(false, info.json.getJSONObject("capabilities").getBoolean("personDetection"))
                assertTrue(info.headers.contains("Cache-Control: no-store"))
                service.updateSettings("Live updated camera", setOf(origin))
                assertEquals("Live updated camera", api(tls, "GET", "/api/v1/info").json!!.getString("name"))
                expectError(api(tls, "GET", "/api/v1/info", origin = "https://approved.example"), 403)
                service.updateSettings(oldName, oldOrigins + origin + "https://approved.example")
                assertTrue(service.pauseHttpsForIdentityChange())
                assertTrue(service.resumeHttpsAfterIdentityChange())
                awaitHealth("disarmed: HTTPS ready")
                assertEquals(200, api(tls, "GET", "/api/v1/info").status)
                expectError(api(tls, "GET", "/api/v1/events"), 401)
                expectError(api(tls, "GET", "/api/v1/info", origin = "https://denied.example"), 403)
                val preflight = api(tls, "OPTIONS", "/api/v1/pair", origin = "https://approved.example",
                    preflight = true)
                assertEquals(204, preflight.status)
                assertTrue(preflight.headers.contains("Access-Control-Allow-Origin: https://approved.example"))
                expectError(api(tls, "GET", "/api/v1/events", origin = "https://approved.example"), 401)
                val code = service.newCode()
                expectError(api(tls, "POST", "/api/v1/pair", origin = "https://denied.example",
                    body = JSONObject().put("code", code).put("viewerName", "intruder").toString()), 403)
                val pairBody = JSONObject().put("code", code).put("viewerName", "Test viewer").toString()
                val paired = api(tls, "POST", "/api/v1/pair", origin = "https://approved.example", body = pairBody)
                assertEquals(201, paired.status)
                val token = paired.json!!.getString("token")
                val grant = PeerState(context).grants.authenticate("Bearer $token")
                assertNotNull(grant)
                issued.add(grant!!.id)
                expectError(api(tls, "POST", "/api/v1/pair", body = pairBody), 401)
                expectError(api(tls, "GET", "/api/v1/events", bearer = "invalid"), 401)
                assertEquals(200, api(tls, "GET", "/api/v1/events", origin = "https://approved.example",
                    bearer = token).status)
                val missing = "00000000-0000-0000-0000-000000000000"
                expectError(api(tls, "PUT", "/api/v1/sessions/$missing", bearer = token), 404)
                expectError(api(tls, "POST", "/api/v1/sessions/$missing/ice", bearer = token,
                    body = """{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":0}"""), 404)
                val second = api(tls, "POST", "/api/v1/pair",
                    body = JSONObject().put("code", service.newCode()).put("viewerName", "Other viewer").toString())
                assertEquals(201, second.status)
                val otherToken = second.json!!.getString("token")
                val secondGrant = PeerState(context).grants.authenticate("Bearer $otherToken")!!
                issued.add(secondGrant.id)
                val slots = PeerService::class.java.getDeclaredField("slots").apply { isAccessible = true }
                    .get(service) as Slots
                val slot = slots.reserve(grant.id, false)!!
                try {
                    expectError(api(tls, "POST", "/api/v1/sessions/${slot.id}/ice", bearer = otherToken,
                        body = """{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":0}"""), 404)
                    state.armed(true)
                    activity.onActivity {
                        ContextCompat.startForegroundService(it, Intent(it, PeerService::class.java))
                    }
                    awaitHealth("running")
                    expectError(api(tls, "POST", "/api/v1/sessions/${slot.id}/ice", bearer = token,
                        body = """{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":2}"""), 400)
                    expectError(api(tls, "POST", "/api/v1/sessions/${slot.id}/ice", bearer = token,
                        body = """{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":0}"""), 503)
                    expectError(api(tls, "PUT", "/api/v1/sessions/${slot.id}", bearer = otherToken), 404)
                    expectError(api(tls, "DELETE", "/api/v1/sessions/${slot.id}", bearer = otherToken), 404)
                    assertEquals(200, api(tls, "PUT", "/api/v1/sessions/${slot.id}", bearer = token).status)
                    service.revoke(grant.id)
                    issued.remove(grant.id)
                    expectError(api(tls, "GET", "/api/v1/events", bearer = token), 401)
                    expectError(api(tls, "POST", "/api/v1/sessions/${slot.id}/ice", bearer = otherToken,
                        body = """{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":0}"""), 404)
                    val current = PeerService::class.java.getDeclaredField("state").apply { isAccessible = true }
                        .get(service) as PeerState
                    current.grants.load(current.grants.list().map {
                        if (it.id == secondGrant.id) it.copy(expires = System.currentTimeMillis() - 1) else it
                    })
                    current.saveGrants()
                    expectError(api(tls, "GET", "/api/v1/events", bearer = otherToken), 401)
                } finally { slots.clear() }
            }
        } finally {
            context.stopService(Intent(context, PeerService::class.java))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (PeerService.instance != null && System.nanoTime() < deadline) Thread.sleep(50)
            val persisted = PeerState(context)
            issued.forEach {
                persisted.grants.revoke(it)
            }
            persisted.saveGrants()
            persisted.saveSettings(oldName, oldOrigins)
            persisted.armed(oldArmed)
            assertNull("Test foreground service did not stop", PeerService.instance)
        }
    }
}
