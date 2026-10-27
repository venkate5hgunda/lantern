package dev.lantern.peer

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.*
import java.security.spec.*

class WebPushCryptoTest {
    @Test fun `encryption exactly matches RFC 8291 section five published vector`() {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, WebPushCrypto.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")), params))
        val publicKey = WebPushCrypto.publicKey(WebPushCrypto.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"))
        val bytes = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".toByteArray(),
            WebPushCrypto.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg"), KeyPair(publicKey, privateKey), WebPushCrypto.decode("DGv6ra1nlYgDCS1FRnbzlw"))
        assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN", WebPushCrypto.encode(bytes))
    }
    @Test fun `VAPID signature is valid ES256 with the exact provider audience`() {
        val keys = WebPushCrypto.generate()
        val authorization = WebPushCrypto.authorization("https://fcm.googleapis.com/fcm/send/test", keys, 1234)
        val jwt = authorization.substringAfter("vapid t=").substringBefore(", k=").split('.')
        assertTrue(String(WebPushCrypto.decode(jwt[1])).contains("\"aud\":\"https://fcm.googleapis.com\""))
        assertTrue(String(WebPushCrypto.decode(jwt[1])).contains("\"exp\":44434"))
        val signature = WebPushCrypto.decode(jwt[2])
        assertEquals(64, signature.size)
        val r = BigInteger(1, signature.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, signature.copyOfRange(32, 64)).toByteArray()
        val der = byteArrayOf(0x30, (4 + r.size + s.size).toByte(), 2, r.size.toByte()) + r + byteArrayOf(2, s.size.toByte()) + s
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(keys.public); update("${jwt[0]}.${jwt[1]}".toByteArray()); verify(der) })
    }
    @Test fun `subscription endpoints cannot proxy requests to arbitrary hosts or origins`() {
        for (value in listOf("http://fcm.googleapis.com/send", "https://fcm.googleapis.com.evil.test/send", "https://user:pass@fcm.googleapis.com/send", "https://127.0.0.1/", "https://fcm.googleapis.com:8443/send", "https://fcm.googleapis.com/send#token")) assertFalse(value, WebPushCrypto.validEndpoint(value))
        assertTrue(WebPushCrypto.validEndpoint("https://updates.push.services.mozilla.com/wpush/v2/test"))
        assertTrue(WebPushCrypto.validEndpoint("https://web.push.apple.com/test"))
    }
}
