package dev.lantern.peer

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Principal
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.net.Socket
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal object Identity {
    private const val ALIAS = "lantern-server"
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    fun fingerprint(): String = mediaHash(store().getCertificate(ALIAS).encoded)
    fun available(): Boolean = store().containsAlias(ALIAS)
    fun origins(): Set<String> = (store().getCertificate(ALIAS) as? X509Certificate)?.let(::certificateOrigins)
        ?: emptySet()
    private fun certificateOrigins(leaf: X509Certificate): Set<String> =
        leaf.subjectAlternativeNames.orEmpty().mapNotNull {
            if (it[0] in listOf(2, 7)) {
                val host = it[1].toString()
                val literal = if (host.contains(':')) "[$host]" else host
                Origins.canonical("https://$literal:8443")
            } else null
        }.toSet()
    fun import(bytes: ByteArray, password: CharArray): Set<String> {
        try {
            require(bytes.size in 1..262_144)
            val incoming = KeyStore.getInstance("PKCS12")
            incoming.load(bytes.inputStream(), password)
            val alias = incoming.aliases().toList().firstOrNull { incoming.isKeyEntry(it) }
                ?: throw IllegalArgumentException("No private key")
            val key = incoming.getKey(alias, password) as? PrivateKey ?: throw IllegalArgumentException("No private key")
            val chain = incoming.getCertificateChain(alias) ?: throw IllegalArgumentException("No certificate chain")
            val leaf = chain.first() as X509Certificate
            leaf.checkValidity()
            require(key.algorithm in listOf("EC", "RSA"))
            val suggested = certificateOrigins(leaf)
            val purposes = KeyProperties.PURPOSE_SIGN or
                if (key.algorithm == "RSA") KeyProperties.PURPOSE_DECRYPT else 0
            val protection = KeyProtection.Builder(purposes)
                // Conscrypt hashes/pads TLS signatures itself before delegating the raw key operation.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            if (key.algorithm == "RSA") protection
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            store().setEntry(ALIAS, KeyStore.PrivateKeyEntry(key, chain), protection.build())
            check(available())
            return suggested
        } finally { password.fill('\u0000'); bytes.fill(0) }
    }
    fun context(): SSLContext {
        val identity = store()
        val chain = identity.getCertificateChain(ALIAS)?.map { it as X509Certificate }?.toTypedArray()
            ?: throw IllegalStateException("Server certificate chain unavailable")
        chain.first().checkValidity()
        val key = identity.getKey(ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Server signing key unavailable")
        require(key.algorithm in listOf("RSA", "EC"))
        val algorithm = if (key.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val proof = Signature.getInstance(algorithm).run {
            initSign(key)
            update(challenge)
            sign()
        }
        check(Signature.getInstance(algorithm).run {
            initVerify(chain.first().publicKey)
            update(challenge)
            verify(proof)
        }) { "Imported TLS key does not match its certificate" }
        val manager = object : X509ExtendedKeyManager() {
            private fun aliasFor(type: String?): String? =
                if (type == key.algorithm || (key.algorithm == "RSA" &&
                        (type?.startsWith("RSA_") == true || type?.startsWith("RSASSA-PSS") == true))) ALIAS else null
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                aliasFor(keyType)?.let { arrayOf(it) }
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                aliasFor(keyType)
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                aliasFor(keyType)
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) chain.clone() else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
        }
        return SSLContext.getInstance("TLS").also { it.init(arrayOf(manager), null, null) }
    }
}

internal data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
}
internal data class Response(val status: Int, val body: ByteArray = byteArrayOf(),
    val mime: String = "application/json; charset=utf-8",
    val onDeliveryFailure: (() -> Unit)? = null) {
    companion object {
        fun error(code: Int, key: String, message: String) = Response(code,
            org.json.JSONObject().put("error", org.json.JSONObject().put("code", key).put("message", message))
                .toString().toByteArray(StandardCharsets.UTF_8))
    }
}

internal class TooLarge : Exception()
internal object HttpRequestReader {
    fun read(input: InputStream): Request {
        val headers = ByteArrayOutputStream()
        var match = 0
        while (headers.size() < 12_288) {
            val value = input.read()
            if (value < 0) throw IllegalArgumentException()
            headers.write(value)
            match = if (value == "\r\n\r\n"[match].code) match + 1 else if (value == '\r'.code) 1 else 0
            if (match == 4) break
        }
        if (match != 4) throw TooLarge()
        val lines = headers.toString(StandardCharsets.ISO_8859_1.name()).dropLast(4).split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size != 3 || parts[2] != "HTTP/1.1" || parts[0] !in listOf("GET", "POST", "PUT", "DELETE", "OPTIONS") ||
            !parts[1].startsWith("/") || parts[1].contains('?') || parts[1].contains('#')) throw IllegalArgumentException()
        val fields = mutableMapOf<String, String>()
        lines.drop(1).forEach {
            val pos = it.indexOf(':')
            if (pos <= 0) throw IllegalArgumentException()
            val key = it.substring(0, pos).lowercase()
            if (!key.matches(Regex("[a-z0-9-]+")) || key in fields) throw IllegalArgumentException()
            fields[key] = it.substring(pos + 1).trim()
        }
        if (fields["transfer-encoding"] != null || fields["host"] == null) throw IllegalArgumentException()
        val length = fields["content-length"]?.let {
            if (!it.matches(Regex("[0-9]+"))) throw IllegalArgumentException()
            val parsed = it.toLongOrNull() ?: throw TooLarge()
            if (parsed > 96 * 1024) throw TooLarge()
            parsed.toInt()
        } ?: 0
        if (length > 96 * 1024) throw TooLarge()
        if (parts[0] in listOf("GET", "DELETE", "PUT", "OPTIONS") && length != 0) throw IllegalArgumentException()
        val body = ByteArray(length)
        var position = 0
        while (position < length) {
            val read = input.read(body, position, length - position)
            if (read < 0) throw IllegalArgumentException()
            position += read
        }
        return Request(parts[0], parts[1], fields, body)
    }
}

internal class SecureServer(
    private val context: Context,
    private val origins: Origins,
    private val handle: (Request) -> Response
) {
    private val pool = ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(16), ThreadPoolExecutor.AbortPolicy())
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<SSLSocket>()
    @Volatile private var socket: SSLServerSocket? = null
    @Volatile private var acceptor: Thread? = null
    fun start() {
        val server = Identity.context().serverSocketFactory.createServerSocket(8443) as SSLServerSocket
        server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket = server
        acceptor = Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept() as SSLSocket
                    clients.add(client)
                    try { pool.execute {
                        try { client.use(::serve) } finally { clients.remove(client) }
                    } }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        clients.remove(client)
                        client.close()
                    }
                } catch (_: SocketException) { break }
                catch (_: Exception) { if (!server.isClosed) continue }
            }
        }.also { it.name = "lantern-https"; it.start() }
    }
    fun stop() {
        socket?.close()
        socket = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        pool.shutdownNow()
    }
    fun awaitStopped() { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS) }
    private fun serve(client: SSLSocket) {
        client.soTimeout = 35_000
        try {
            val input = BufferedInputStream(client.inputStream)
            val request = try { HttpRequestReader.read(input) } catch (_: TooLarge) {
                write(client, Response.error(413, "body_too_large", "Request exceeds limit"), null); return
            } catch (_: Exception) {
                write(client, Response.error(400, "bad_request", "Malformed request"), null); return
            }
            val origin = request.header("origin")
            if (!origins.accepts(origin)) {
                write(client, Response.error(403, "origin_denied", "Origin is not approved"), null); return
            }
            if (request.method == "OPTIONS") {
                val valid = request.path.startsWith("/api/v1/") &&
                    request.header("access-control-request-method") in listOf("GET", "POST", "PUT", "DELETE")
                val response = if (origin == null || !valid) Response.error(400, "bad_preflight", "Invalid preflight")
                    else Response(204)
                try { write(client, response, origin) }
                catch (failure: Exception) {
                    try { response.onDeliveryFailure?.invoke() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
                return
            }
            val response = try { handle(request) } catch (_: Exception) {
                Response.error(503, "unavailable", "Service unavailable")
            }
            write(client, response, origin)
        } catch (_: Exception) { /* TLS client disconnected. */ }
    }
    private fun write(client: SSLSocket, response: Response, origin: String?) {
        val reason = when (response.status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"
            401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
            413 -> "Content Too Large"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable"
            504 -> "Gateway Timeout"; else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Length: ${response.body.size}\r\nContent-Type: ${response.mime}\r\n")
            append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src https:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n")
            if (origin != null) {
                append("Access-Control-Allow-Origin: $origin\r\nVary: Origin\r\n")
                append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
                append("Access-Control-Max-Age: 300\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        client.outputStream.write(headers.toByteArray(StandardCharsets.US_ASCII))
        client.outputStream.write(response.body)
        client.outputStream.flush()
    }
    fun static(path: String): Response {
        val asset = if (path == "/") "index.html" else path.removePrefix("/")
        if (!asset.matches(Regex("[A-Za-z0-9_./-]+")) || asset.split('/').any { it == ".." }) {
            return Response.error(404, "not_found", "Not found")
        }
        val mime = when {
            asset.endsWith(".webmanifest") -> "application/manifest+json"
            asset.endsWith(".html") -> "text/html; charset=utf-8"
            asset.endsWith(".js") -> "text/javascript; charset=utf-8"
            asset.endsWith(".css") -> "text/css; charset=utf-8"
            asset.endsWith(".svg") -> "image/svg+xml"
            asset.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
        return try { Response(200, context.assets.open(asset).use { it.readBytes() }, mime) }
        catch (_: Exception) { Response.error(503, "viewer_unavailable", "Viewer assets not installed") }
    }
}
