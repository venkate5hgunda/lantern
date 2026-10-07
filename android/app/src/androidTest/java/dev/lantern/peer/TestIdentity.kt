package dev.lantern.peer

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

internal class TestIdentity(
    val bytes: ByteArray, val password: CharArray, val leaf: X509Certificate, val root: X509Certificate
) : Closeable {
    override fun close() { bytes.fill(0); password.fill('\u0000') }
}

internal fun createTestIdentity(includeAddress: Boolean = true): TestIdentity {
    val caPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val caName = X500Name("CN=Lantern ephemeral test CA")
    val name = X500Name("CN=Lantern ephemeral device test")
    val now = System.currentTimeMillis()
    val caBuilder = JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(now + 1),
        Date(now - 60_000), Date(now + 3_600_000), caName, caPair.public)
    caBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    caBuilder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
    val caCert = JcaX509CertificateConverter().getCertificate(caBuilder.build(
        JcaContentSignerBuilder("SHA256withRSA").build(caPair.private)))
    val builder = JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(now),
        Date(now - 60_000), Date(now + 3_600_000), name, pair.public)
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
    builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
    if (includeAddress) builder.addExtension(Extension.subjectAlternativeName, false,
        GeneralNames(GeneralName(GeneralName.iPAddress, "127.0.0.1")))
    val cert = JcaX509CertificateConverter().getCertificate(builder.build(
        JcaContentSignerBuilder("SHA256withRSA").build(caPair.private)))
    val password = java.util.Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)).toCharArray()
    val p12 = KeyStore.getInstance("PKCS12").also {
        it.load(null, password)
        it.setKeyEntry("lantern", pair.private, password, arrayOf(cert, caCert))
    }
    val bytes = ByteArrayOutputStream().use { p12.store(it, password); it.toByteArray() }
    return TestIdentity(bytes, password, cert, caCert)
}
