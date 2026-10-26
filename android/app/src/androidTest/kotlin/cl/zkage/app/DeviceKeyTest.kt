package cl.zkage.app

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.zkage.verifier.MlDsa
import java.io.File
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deletes the app's device keys, so it runs only on an emulator: on a real phone it would
 * destroy the key the stored proof is bound to.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val challenge = "challenge".toByteArray()

    @Before
    fun onEmulatorWithNoKeys() {
        assumeTrue("emulator only", Build.HARDWARE == "ranchu")
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(DeviceKey.SIGNING_ALIAS)
            deleteEntry(DeviceKey.WRAPPING_ALIAS)
        }
        File(context.filesDir, DeviceKey.SEED_FILE).delete()
    }

    private fun DeviceKey.signsVerifiably() = assertTrue(MlDsa.verify(spki, challenge, sign(challenge)))

    /**
     * On API 37 the key comes from the Keystore when its KeyMint has ML-DSA, and from software,
     * saying why, when it does not (the emulator's SoftKeymaster, for one).
     */
    @Test
    fun keystoreKeyOrExplainedFallbackOnApi37() {
        assumeTrue(Build.VERSION.SDK_INT >= 37)
        val key = DeviceKey.forNewProof(context)
        assertTrue(key.storage, key.storage.startsWith("Android Keystore") || "el Keystore no generó ML-DSA" in key.storage)
        key.signsVerifiably()
        assertArrayEquals(key.spki, DeviceKey.forNewProof(context).spki)
    }

    @Test
    fun softwareKeySurvivesReload() {
        val key = DeviceKey.forNewProof(context, keystoreMlDsa = false)
        assertTrue(key.storage, key.storage.startsWith("software"))
        key.signsVerifiably()
        val again = DeviceKey.matching(context, key.spkiSha256)!!
        assertArrayEquals(key.spki, again.spki)
        again.signsVerifiably()
    }

    /** A phone that had a software key and proof updates to API 37 and proves again. */
    @Test
    fun upgradeMovesToKeystoreOnlyAfterTheNewProof() {
        assumeTrue(Build.VERSION.SDK_INT >= 37)
        val old = DeviceKey.forNewProof(context, keystoreMlDsa = false)
        val new = DeviceKey.forNewProof(context)
        assumeTrue("this KeyMint has no ML-DSA: ${new.storage}", new.storage.startsWith("Android Keystore"))
        // Before the new proof is stored, the old proof's key still signs.
        DeviceKey.matching(context, old.spkiSha256)!!.signsVerifiably()
        DeviceKey.retireOthers(context, new)
        assertNull(DeviceKey.matching(context, old.spkiSha256))
        DeviceKey.matching(context, new.spkiSha256)!!.signsVerifiably()
    }
}
