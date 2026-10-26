package cl.zkage.verifier

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chip simulated with Bouncy Castle's ISO 9796-2 signer, independent of [ActiveAuth]'s parser. */
class ActiveAuthTest {
    private val random = SecureRandom()
    private val challenge = ByteArray(8).also(random::nextBytes)

    private fun dg15(spki: ByteArray): ByteArray {
        val len = when {
            spki.size < 128 -> byteArrayOf(spki.size.toByte())
            spki.size < 256 -> byteArrayOf(0x81.toByte(), spki.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (spki.size shr 8).toByte(), spki.size.toByte())
        }
        return byteArrayOf(0x6f) + len + spki
    }

    /**
     * What an RSA chip answers: M1 is exactly what fits in the block next to the header, hash
     * and trailer, and the challenge is the part the verifier supplies.
     */
    private fun rsaChip(bits: Int, digest: Digest, implicit: Boolean): Pair<ByteArray, (ByteArray) -> ByteArray> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
        val private = pair.private as RSAPrivateCrtKey
        val sign = { c: ByteArray ->
            val signer = ISO9796d2Signer(RSAEngine(), digest, implicit)
            signer.init(true, RSAKeyParameters(true, private.modulus, private.privateExponent))
            val m1 = ByteArray(bits / 8 - 1 - digest.digestSize - (if (implicit) 1 else 2)).also(random::nextBytes)
            signer.update(m1, 0, m1.size)
            signer.update(c, 0, c.size)
            signer.generateSignature()
        }
        return dg15(pair.public.encoded) to sign
    }

    @Test
    fun rsaSha1ImplicitTrailer() {
        val (dg15, sign) = rsaChip(1024, SHA1Digest(), implicit = true)
        val response = sign(challenge)
        assertEquals(ActiveAuth.Result("RSA", 1024, "SHA-1", true), ActiveAuth.verify(dg15, challenge, response))
        assertFalse(ActiveAuth.verify(dg15, challenge.copyOf().also { it[0] = (it[0] + 1).toByte() }, response).valid)
    }

    @Test
    fun rsaSha256ExplicitTrailer() {
        val (dg15, sign) = rsaChip(2048, SHA256Digest(), implicit = false)
        val response = sign(challenge)
        assertEquals(ActiveAuth.Result("RSA", 2048, "SHA-256", true), ActiveAuth.verify(dg15, challenge, response))
        assertFalse(ActiveAuth.verify(dg15, ByteArray(8), response).valid)
        assertFalse(ActiveAuth.verify(dg15, challenge, response.copyOf().also { it[10] = (it[10] + 1).toByte() }).valid)
    }

    @Test
    fun ecPlainSignature() {
        val bc = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("EC", bc).apply { initialize(ECGenParameterSpec("brainpoolP256r1")) }.generateKeyPair()
        val response = Signature.getInstance("SHA256withPLAIN-ECDSA", bc).run {
            initSign(pair.private)
            update(challenge)
            sign()
        }
        val dg15 = dg15(pair.public.encoded)
        val sha256 = "0.4.0.127.0.7.1.1.4.1.3"
        assertEquals(ActiveAuth.Result("EC", 256, "SHA-256", true, "brainpoolP256r1"), ActiveAuth.verify(dg15, challenge, response, sha256))
        assertFalse(ActiveAuth.verify(dg15, ByteArray(8), response, sha256).valid)
        assertThrows(VerificationException::class.java) { ActiveAuth.verify(dg15, challenge, response) }
    }

    @Test
    fun explicitCurveParametersAreNamed() {
        // Many chips put the full curve parameters in DG15 instead of the curve's OID.
        val bc = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("EC", bc).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val named = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(pair.public.encoded)
        val oid = named.algorithm.parameters as org.bouncycastle.asn1.ASN1ObjectIdentifier
        val explicit = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(named.algorithm.algorithm, org.bouncycastle.asn1.x9.ECNamedCurveTable.getByOID(oid)),
            named.publicKeyData.bytes,
        )
        val response = Signature.getInstance("SHA256withPLAIN-ECDSA", bc).run {
            initSign(pair.private)
            update(challenge)
            sign()
        }
        val result = ActiveAuth.verify(dg15(explicit.encoded), challenge, response, "0.4.0.127.0.7.1.1.4.1.3")
        assertTrue(result.valid)
        assertTrue(result.curve, result.curve in setOf("secp256r1", "prime256v1", "P-256"))
    }

    @Test
    fun otherChipsKeyIsRejected() {
        val (_, sign) = rsaChip(2048, SHA256Digest(), implicit = false)
        val (otherDg15, _) = rsaChip(2048, SHA256Digest(), implicit = false)
        assertFalse(ActiveAuth.verify(otherDg15, challenge, sign(challenge)).valid)
    }

    @Test
    fun dg15MustBeInTheSod() {
        fun fixture(name: String) = javaClass.classLoader.getResourceAsStream(name)!!.readBytes()
        val (sod, dg1) = fixture("sod.der") to fixture("dg1.bin")
        assertNull(SodVerifier.verify(sod, dg1).dg15HashValid)
        assertTrue(SodVerifier.verify(sod, dg1, dg15 = fixture("dg15.bin")).dg15HashValid!!)
        // Another chip's key: what a copy of the files with an attacker's key would carry.
        assertFalse(SodVerifier.verify(sod, dg1, dg15 = rsaChip(1024, SHA1Digest(), true).first).dg15HashValid!!)
    }

    /** The mock card's chip, as `prover/fixtures/gen.py` makes it: P-256, which the proof can check. */
    @Test
    fun mockChipIsProvable() {
        fun fixture(name: String) = javaClass.classLoader.getResourceAsStream(name)!!.readBytes()
        val key = java.security.KeyFactory.getInstance("EC", BouncyCastleProvider())
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(fixture("chip_aa_key.der")))
        val response = Signature.getInstance("SHA256withPLAIN-ECDSA", BouncyCastleProvider()).run {
            initSign(key)
            update(challenge)
            sign()
        }
        val result = ActiveAuth.verify(fixture("dg15.bin"), challenge, response, "0.4.0.127.0.7.1.1.4.1.3")
        assertTrue(result.provable)
        assertFalse(result.copy(curve = "brainpoolP256r1").provable)
    }
}
