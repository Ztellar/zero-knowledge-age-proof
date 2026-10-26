package cl.zkage.app

import android.app.ActivityManager
import android.content.Context

/**
 * The zkVM segment size for this phone. Larger segments prove faster but need more memory;
 * the proof and its security are the same at every size (decision 0006).
 *
 * Measured on a Galaxy S25 with the mock card, 2026-10-26 (docs/measurements/active-auth.md):
 *
 * | po2 | segments | time | peak memory |
 * | --- | --- | --- | --- |
 * | 19 | 1 | 128.6 s | 4.41 GB |
 * | 18 | 3 | 195.4 s | 2.47 GB |
 * | 17 | 6 | 366.1 s | 1.52 GB |
 *
 * The largest size whose peak stays within [BUDGET] of the phone's RAM is used: a judgment,
 * not a measurement, leaving room for the system and other apps.
 */
data class SegmentSize(val po2: Int, val reason: String) {
    companion object {
        /** Share of total RAM the prover may peak at. */
        const val BUDGET = 0.4

        /** Measured peaks in bytes (VmHWM × 1024), largest segment first. */
        private val peaks = listOf(19 to 4_413_374_464L, 18 to 2_470_883_328L, 17 to 1_515_839_488L)

        fun forDevice(context: Context): SegmentSize {
            val am = context.getSystemService(ActivityManager::class.java)
            val total = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
            return forTotalMemory(total, am.isLowRamDevice)
        }

        fun forTotalMemory(totalBytes: Long, lowRam: Boolean): SegmentSize {
            val gb = "%.1f GB de RAM".format(totalBytes / 1e9)
            if (!lowRam) {
                peaks.firstOrNull { (_, peak) -> peak <= totalBytes * BUDGET }?.let { (po2, _) -> return SegmentSize(po2, gb) }
            }
            // The smallest measured size; below this the phone may not finish at all.
            return SegmentSize(17, "$gb, menos de lo recomendado")
        }
    }
}
