package dev.lantern.peer

import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 8291/8188 payload encryption and RFC 8292 VAPID, using JCA primitives. */
internal object WebPushCrypto {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private fun coordinate(value: BigInteger): ByteArray = value.toByteArray().takeLast(32).toByteArray().let {
        ByteArray(32 - it.size) + it
    }
    fun publicBytes(key: PublicKey): ByteArray = (key as ECPublicKey).w.let { byteArrayOf(4) + coordinate(it.affineX) + coordinate(it.affineY) }
    fun publicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte())
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65))), params))
    }
    fun privateKey(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value) }
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray = hmac(prk, info + byteArrayOf(1)).copyOf(length)
    fun encrypt(payload: ByteArray, clientPublic: ByteArray, auth: ByteArray, sender: KeyPair = generate(), salt: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)): ByteArray {
        require(auth.size == 16 && salt.size == 16 && payload.size <= 3000)
        val serverPublic = publicBytes(sender.public)
        val shared = KeyAgreement.getInstance("ECDH").run { init(sender.private); doPhase(publicKey(clientPublic), true); generateSecret() }
        val ikm = expand(hmac(auth, shared), "WebPush: info\u0000".toByteArray() + clientPublic + serverPublic, 32)
        val prk = hmac(salt, ikm)
        val key = expand(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(), 16)
        val nonce = expand(prk, "Content-Encoding: nonce\u0000".toByteArray(), 12)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(payload + byteArrayOf(2))
        }
        return salt + ByteBuffer.allocate(4).putInt(4096).array() + byteArrayOf(65) + serverPublic + encrypted
    }
    fun authorization(endpoint: String, keys: KeyPair, nowSeconds: Long): String {
        require(validEndpoint(endpoint))
        val uri = URI(endpoint)
        val header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".toByteArray())
        val claims = encode(("{\"aud\":\"https://${uri.host.lowercase(java.util.Locale.ROOT)}\",\"exp\":${nowSeconds + 12 * 3600}," +
            "\"sub\":\"https://github.com/venkate5hgunda/lantern\"}").toByteArray())
        val signingInput = "$header.$claims"
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(signingInput.toByteArray()); sign() }
        return "vapid t=$signingInput.${encode(joseSignature(der))}, k=${encode(publicBytes(keys.public))}"
    }
    fun joseSignature(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte() && der[1].toInt() == der.size - 2 && der[2] == 2.toByte())
        val rLength = der[3].toInt()
        require(rLength in 1..33 && 4 + rLength + 2 < der.size && der[4 + rLength] == 2.toByte())
        val sLength = der[5 + rLength].toInt()
        require(sLength in 1..33 && 6 + rLength + sLength == der.size)
        return coordinate(BigInteger(1, der.copyOfRange(4, 4 + rLength))) + coordinate(BigInteger(1, der.copyOfRange(6 + rLength, der.size)))
    }
    fun validEndpoint(value: String): Boolean = try {
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: ""
        value.length <= 4096 && uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawFragment == null &&
            (host == "fcm.googleapis.com" || host == "updates.push.services.mozilla.com" || host.endsWith(".push.services.mozilla.com") || host == "web.push.apple.com" || host.endsWith(".notify.windows.com"))
    } catch (_: Exception) { false }
}
