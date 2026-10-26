package cl.zkage.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import cl.zkage.verifier.MlDsa
import java.io.File
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's ML-DSA-65 key (decision 0004). The proof carries the SHA-256 of [spki]; each
 * presentation is a signature with this key.
 *
 * Where it lives depends on the phone:
 * - **API 37+**: generated inside the Android Keystore, so the private key never leaves the
 *   secure hardware.
 * - **Older**: Bouncy Castle in the app's memory, with the 32-byte seed stored encrypted by an
 *   AES-256-GCM key from the Keystore (StrongBox when the phone has it). The seed cannot be
 *   decrypted on another phone, but while signing it is in the app's memory.
 *
 * A phone that updates to API 37 keeps its software key, and the proof bound to it, until the
 * next proof: that one gets a Keystore key, and [retireOthers] then deletes the software one.
 * Keys are looked up by the hash the stored proof carries ([matching]), so a proof that fails
 * halfway through the switch leaves the previous proof usable.
 */
sealed class DeviceKey {
    abstract val spki: ByteArray
    abstract val storage: String
    abstract fun sign(message: ByteArray): ByteArray

    val spkiSha256: ByteArray get() = MlDsa.spkiSha256(spki)

    private class Hardware(private val private: PrivateKey, override val spki: ByteArray) : DeviceKey() {
        override val storage = "Android Keystore (${securityLevel(private)})"
        override fun sign(message: ByteArray): ByteArray = Signature.getInstance("ML-DSA").run {
            initSign(private)
            update(message)
            sign()
        }
    }

    private class Software(private val key: MlDsa.SoftwareKey, override val storage: String) : DeviceKey() {
        override val spki = key.spki
        override fun sign(message: ByteArray) = key.sign(message)
    }

    companion object {
        internal const val SIGNING_ALIAS = "zkage-device-mldsa"
        internal const val WRAPPING_ALIAS = "zkage-device-seed-wrap"
        internal const val SEED_FILE = "device-key.bin"
        private const val GCM_IV_BYTES = 12

        /**
         * The key for a new proof: the Keystore one on API 37+ (created if needed), otherwise
         * the software one (created if needed). Blocking: Keystore calls can be slow.
         * [keystoreMlDsa] is false only in tests, to stand in for a phone before API 37.
         */
        @Synchronized
        fun forNewProof(context: Context, keystoreMlDsa: Boolean = Build.VERSION.SDK_INT >= 37): DeviceKey {
            val keyStore = keyStore()
            if (keystoreMlDsa) loadHardware(keyStore)?.let { return it }
            var note = ""
            if (keystoreMlDsa && Build.VERSION.SDK_INT >= 37) {
                // A phone on API 37 whose KeyMint lacks ML-DSA falls back to software.
                try {
                    return createHardware()
                } catch (e: Exception) {
                    note = "; el Keystore no generó ML-DSA (${e.javaClass.simpleName}: ${e.message})"
                }
            }
            val seedFile = File(context.filesDir, SEED_FILE)
            return if (seedFile.exists()) loadSoftware(keyStore, seedFile, note) else createSoftware(keyStore, seedFile, note)
        }

        /** The existing key whose public key hashes to [spkiSha256], as a stored proof names it. */
        @Synchronized
        fun matching(context: Context, spkiSha256: ByteArray): DeviceKey? {
            val keyStore = keyStore()
            val seedFile = File(context.filesDir, SEED_FILE)
            val candidates = sequence {
                loadHardware(keyStore)?.let { yield(it) }
                if (seedFile.exists()) yield(loadSoftware(keyStore, seedFile))
            }
            return candidates.firstOrNull { it.spkiSha256.contentEquals(spkiSha256) }
        }

        /** Deletes every key except [kept], once a proof bound to [kept] is stored. */
        @Synchronized
        fun retireOthers(context: Context, kept: DeviceKey) {
            val keyStore = keyStore()
            when (kept) {
                is Hardware -> {
                    File(context.filesDir, SEED_FILE).delete()
                    keyStore.deleteEntry(WRAPPING_ALIAS)
                }
                is Software -> keyStore.deleteEntry(SIGNING_ALIAS)
            }
        }

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun loadHardware(keyStore: KeyStore): DeviceKey? =
            (keyStore.getEntry(SIGNING_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
                Hardware(it.privateKey, it.certificate.publicKey.encoded)
            }

        /** StrongBox first; ML-DSA in StrongBox is rarer than in the TEE. */
        @RequiresApi(37)
        private fun createHardware(): DeviceKey {
            fun generate(strongBox: Boolean) =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_ML_DSA_65, "AndroidKeyStore").run {
                    initialize(
                        KeyGenParameterSpec.Builder(SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setIsStrongBoxBacked(strongBox)
                            .build(),
                    )
                    generateKeyPair()
                }
            val pair = try {
                generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
            return Hardware(pair.private, pair.public.encoded)
        }

        private fun createSoftware(keyStore: KeyStore, seedFile: File, note: String): DeviceKey {
            val wrapping = keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey ?: newWrappingKey()
            val seed = MlDsa.newSeed()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping) }
            seedFile.writeBytes(cipher.iv + cipher.doFinal(seed))
            return Software(MlDsa.SoftwareKey(seed), softwareStorage(wrapping) + note).also { seed.fill(0) }
        }

        private fun loadSoftware(keyStore: KeyStore, seedFile: File, note: String = ""): DeviceKey {
            val wrapping = keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey
                ?: error("the key that encrypts the device key is gone; the proof must be generated again")
            val blob = seedFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, blob, 0, GCM_IV_BYTES))
            }
            val seed = cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
            return Software(MlDsa.SoftwareKey(seed), softwareStorage(wrapping) + note).also { seed.fill(0) }
        }

        private fun newWrappingKey(): SecretKey {
            fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
                generateKey()
            }
            return try {
                generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }

        private fun softwareStorage(wrapping: SecretKey) =
            "software, semilla cifrada con AES del Keystore (${securityLevel(wrapping)})"

        private fun securityLevel(key: Key): String {
            if (Build.VERSION.SDK_INT < 31) return "nivel sin informar"
            val info = when (key) {
                is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
                else -> KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
            } as KeyInfo
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                else -> "software"
            }
        }
    }
}
