package cl.zkage.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val start = System.nanoTime()
        val proof = NativeProver.prove(
            fixture("csca_spki.der"), fixture("sod.der"), fixture("dg1.bin"),
            LocalDate.of(2026, 10, 22), 18, deviceKey,
        )
        val seconds = (System.nanoTime() - start) / 1e9
        val peak = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }
        Log.i("NativeProverTest", "proved in %.1f s, %d B, %s".format(seconds, proof.receipt.size, peak))
        assertEquals(18, proof.claim.minAge)
        assertArrayEquals(deviceKey, proof.claim.deviceKeySha256)
    }
}
