package cl.zkage.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDate
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the mock card's claim inside the app's process, as the service does: checks that
 * the native library loads (also on 16 KB page devices) and that the app is not killed for
 * its memory. Takes minutes.
 */
@RunWith(AndroidJUnit4::class)
class NativeProverTest {
    private fun fixture(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun provesMockCard() {
        val deviceKey = ByteArray(32) { 0xd0.toByte() }
        // The mock chip's Active Authentication answer for this device key (decision 0006).
        val bc = BouncyCastleProvider()
        val chip = KeyFactory.getInstance("EC", bc).generatePrivate(PKCS8EncodedKeySpec(fixture("chip_aa_key.der")))
        val aaSignature = Signature.getInstance("SHA256withPLAIN-ECDSA", bc).run {
            initSign(chip)
            update(deviceKey.copyOf(8))
            sign()
        }
        val segment = SegmentSize.forDevice(InstrumentationRegistry.getInstrumentation().targetContext)
        val start = System.nanoTime()
        val proof = NativeProver.prove(
            fixture("csca_spki.der"), fixture("sod.der"), fixture("dg1.bin"),
            LocalDate.of(2026, 10, 22), 18, deviceKey, fixture("dg15.bin"), aaSignature, segment.po2,
        )
        val seconds = (System.nanoTime() - start) / 1e9
        val peak = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }
        Log.i("NativeProverTest", "proved in %.1f s, %d B, %s, segment 2^%d".format(seconds, proof.receipt.size, peak, segment.po2))
        assertEquals(18, proof.claim.minAge)
        assertArrayEquals(deviceKey, proof.claim.deviceKeySha256)
        assertTrue(proof.claim.chipAuthenticated)
    }
}
