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
 *   secure hardware. Not yet run on a real phone: the Galaxy S25 is on API 36.
 * - **Older**: Bouncy Castle in the app's memory, with the 32-byte seed stored encrypted by an
 *   AES-256-GCM key from the Keystore (StrongBox when the phone has it). The seed cannot be
 *   decrypted on another phone, but while signing it is in the app's memory.
 */
sealed class DeviceKey {
    abstract val spki: ByteArray
    abstract val storage: String
    abstract fun sign(message: ByteArray): ByteArray

    val spkiSha256: ByteArray get() = MlDsa.spkiSha256(spki)

    private class Hardware(private val private: PrivateKey, override val spki: ByteArray) : DeviceKey() {
        override val storage = "Android Keystore"
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
        private const val SIGNING_ALIAS = "zkage-device-mldsa"
        private const val WRAPPING_ALIAS = "zkage-device-seed-wrap"
        private const val SEED_FILE = "device-key.bin"
        private const val GCM_IV_BYTES = 12

        /** Loads the key, creating it on first use. Blocking: Keystore calls can be slow. */
        @Synchronized
        fun getOrCreate(context: Context): DeviceKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val seedFile = File(context.filesDir, SEED_FILE)
            (keyStore.getEntry(SIGNING_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
                return Hardware(it.privateKey, it.certificate.publicKey.encoded)
            }
            if (seedFile.exists()) return loadSoftware(keyStore, seedFile)
            if (Build.VERSION.SDK_INT >= 37) {
                // Phones on API 37 without ML-DSA in their KeyMint fall back to software.
                runCatching { return createHardware() }
            }
            return createSoftware(keyStore, seedFile)
        }

        @RequiresApi(37)
        private fun createHardware(): DeviceKey {
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_ML_DSA_65, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN).build())
                generateKeyPair()
            }
            return Hardware(pair.private, pair.public.encoded)
        }

        private fun createSoftware(keyStore: KeyStore, seedFile: File): DeviceKey {
            val wrapping = keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey ?: newWrappingKey()
            val seed = MlDsa.newSeed()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping) }
            seedFile.writeBytes(cipher.iv + cipher.doFinal(seed))
            return Software(MlDsa.SoftwareKey(seed), softwareStorage(wrapping)).also { seed.fill(0) }
        }

        private fun loadSoftware(keyStore: KeyStore, seedFile: File): DeviceKey {
            val wrapping = keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey
                ?: error("the key that encrypts the device key is gone; the proof must be generated again")
            val blob = seedFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, blob, 0, GCM_IV_BYTES))
            }
            val seed = cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
            return Software(MlDsa.SoftwareKey(seed), softwareStorage(wrapping)).also { seed.fill(0) }
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

        private fun softwareStorage(wrapping: SecretKey): String {
            val where = if (Build.VERSION.SDK_INT >= 31) {
                val info = SecretKeyFactory.getInstance(wrapping.algorithm, "AndroidKeyStore")
                    .getKeySpec(wrapping, KeyInfo::class.java) as KeyInfo
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                    else -> "software"
                }
            } else {
                "Keystore"
            }
            return "software, semilla cifrada con AES del Keystore ($where)"
        }
    }
}
