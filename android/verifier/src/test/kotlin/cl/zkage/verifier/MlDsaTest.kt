package cl.zkage.verifier

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlDsaTest {
    private val key = MlDsa.SoftwareKey(ByteArray(MlDsa.SEED_BYTES) { it.toByte() })
    private val challenge = "challenge".toByteArray()

    @Test
    fun signsAndVerifies() {
        val signature = key.sign(challenge)
        // FIPS 204, table 2: ML-DSA-65 public keys are 1952 bytes and signatures 3309.
        assertEquals(3309, signature.size)
        assertTrue(MlDsa.verify(key.spki, challenge, signature))
    }

    @Test
    fun rejectsOtherMessageAndOtherKey() {
        val signature = key.sign(challenge)
        assertFalse(MlDsa.verify(key.spki, "other".toByteArray(), signature))
        val other = MlDsa.SoftwareKey(MlDsa.newSeed())
        assertFalse(MlDsa.verify(other.spki, challenge, signature))
        assertFalse(MlDsa.verify(ByteArray(10), challenge, signature))
    }

    @Test
    fun seedDeterminesKey() {
        val again = MlDsa.SoftwareKey(ByteArray(MlDsa.SEED_BYTES) { it.toByte() })
        assertArrayEquals(key.spki, again.spki)
    }

    /** Same seed, same key as the Rust verifier's `ml-dsa` crate (FIPS 204 KeyGen_internal). */
    @Test
    fun rustSeedMatches() {
        val expected = java.io.File("../../prover/verifier/tests/seed-00-1f.sha256").readText().trim()
        val hex = MlDsa.spkiSha256(key.spki).joinToString("") { "%02x".format(it) }
        assertEquals(expected, hex)
    }

    /** Interop with a JCA `Signature("ML-DSA")`, the API the Android Keystore exposes. */
    @Test
    fun matchesJcaEncodingAndSignatures() {
        val bc = BouncyCastleProvider()
        val public = KeyFactory.getInstance("ML-DSA", bc).generatePublic(X509EncodedKeySpec(key.spki))
        assertEquals("ML-DSA-65", public.algorithm)
        assertArrayEquals(key.spki, public.encoded)
        // 1952-byte key plus the SubjectPublicKeyInfo header.
        assertEquals(1974, key.spki.size)

        val signature = key.sign(challenge)
        val jca = Signature.getInstance("ML-DSA", bc).apply {
            initVerify(public)
            update(challenge)
        }
        assertTrue(jca.verify(signature))

        val pair = java.security.KeyPairGenerator.getInstance("ML-DSA-65", bc).generateKeyPair()
        val jcaSignature = Signature.getInstance("ML-DSA", bc).run {
            initSign(pair.private)
            update(challenge)
            sign()
        }
        assertTrue(MlDsa.verify(pair.public.encoded, challenge, jcaSignature))
    }
}
