# Stage 2 — Measurement on Android

Date: 2026-10-22. Phone: Samsung Galaxy S25 (SM-S931B), Snapdragon SM8750, 8 cores, 11.4 GB
of RAM (`MemTotal` 11,380,220 kB), Android 16, CPU only. RISC Zero 3.0.6 with the prover
inside the process (the host's `prove` feature), because there is no `r0vm` on Android.
Homebrew's NDK, API 29.

## What is proven

The same as in [stage 1](stage-1-desktop.md), with the already optimized guest: the test ID
card, age 18 or over on 2026-10-22, a valid document. Same cycles as on the Mac: 198,377 for
the program, 262,144 in total, one segment.

To reproduce it: the default test ID card now uses the real key sizes (3072/2048); this
measurement used `gen.py --csca-bits 4096 --dsc-bits 4096`. See
[key sizes](chile-key-sizes.md).

## Results

| Receipt | Run | Proving time | Peak RAM | Size | Verification |
| --- | --- | --- | --- | --- | --- |
| Succinct | 1 | 64.70 s | 2.34 GB | 223,514 B | 16.7 ms |
| Succinct | 2 | 64.98 s | 2.47 GB | 223,514 B | 17.5 ms |
| Composite | 1 | 46.93 s | 2.47 GB | 256,250 B | 20.2 ms |

GB = 10^9 bytes, as in stage 1. `VmHWM` is in KiB: 2,288,176 KiB × 1,024 =
2,343,092,224 B; 2,408,192 KiB = 2,465,988,608 B; 2,409,048 KiB = 2,466,865,152 B. On the
Mac the succinct run reached 2,571,370,496 B, adding the host and `r0vm`. The runs were back
to back, with the phone connected over USB and the screen on.

How it was measured: [the measurement script](../../prover/scripts/android-measure.sh)
builds the host for `aarch64-linux-android`, copies it with the fixtures over `adb`, and
reads the process's `VmHWM` every 0.5 s. `VmHWM` is the maximum the kernel keeps, so the last
reading is the peak.

## Reading

- **RISC Zero builds and runs on Android unchanged.** Plonky3 is not needed.
- **The phone takes about 2 times the Mac's time:** 64.7 s / 32.8 s = 1.97 for the succinct
  proof; 46.9 s / 22.8 s = 2.06 for the composite one.
- **Memory fits, but barely.** Before the proof Android reported `MemAvailable`
  2,565,356 kB (2.6 GB) and the process reached 2.47 GB without the system killing it. With
  more apps open, or on a 6–8 GB phone, it could die: it has to be tested inside the real
  app.
- **The composite proof is 18 s faster** but is 32,736 bytes larger (256,250 − 223,514). To
  register an ID card only once, the size difference does not matter; the time difference
  is noticeable.
- **Heat and battery were not measured.** Reading the battery temperature needs permissions
  `adb shell` does not have. It was not measured with the screen off or with the app in the
  background either, where Android may throttle the process.
