package cl.zkage.verifier

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.signers.MLDSASigner
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory

/**
 * ML-DSA-65 (FIPS 204), the phone's key in decision 0004: the proof publishes the SHA-256 of
 * its public key and each presentation is signed with it. Pure ML-DSA with an empty context,
 * which is also what a JCA `Signature("ML-DSA")` produces (tested against Bouncy Castle's
 * JCA provider; not yet against an Android Keystore key, which needs API 37).
 *
 * Uses Bouncy Castle's low-level classes rather than a JCA provider: Android ships its own
 * stripped "BC" provider under the same name.
 */
object MlDsa {
    private val params = MLDSAParameters.ml_dsa_65

    /** A key held in memory, derived from its 32-byte seed (FIPS 204 ξ). */
    class SoftwareKey(seed: ByteArray) {
        init {
            require(seed.size == SEED_BYTES) { "ML-DSA seed must be $SEED_BYTES bytes" }
        }

        private val private = MLDSAPrivateKeyParameters(params, seed)

        /** DER SubjectPublicKeyInfo, the encoding whose SHA-256 goes into the proof. */
        val spki: ByteArray =
            SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(private.publicKeyParameters).encoded

        fun sign(message: ByteArray): ByteArray = MLDSASigner().run {
            init(true, private)
            update(message, 0, message.size)
            generateSignature()
        }
    }

    const val SEED_BYTES = 32

    fun newSeed(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SEED_BYTES).also(random::nextBytes)

    /** False for a bad signature and for a key that is not ML-DSA-65. */
    fun verify(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val key = runCatching { PublicKeyFactory.createKey(spki) }.getOrNull() as? MLDSAPublicKeyParameters ?: return false
        if (key.parameters != params) return false
        return MLDSASigner().run {
            init(false, key)
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }

    fun spkiSha256(spki: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(spki)
}
