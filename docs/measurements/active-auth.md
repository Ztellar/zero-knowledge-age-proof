# Active Authentication inside the proof — Measurement

Date: 2026-10-26. Program with check 6 of
[decision 0006](../decisions/0006-active-authentication-in-proof.md): DG15 against the SOD,
and the chip's ECDSA P-256 signature over the first 8 bytes of `device_key_sha256`. Test ID
card from [`gen.py`](../../prover/fixtures/gen.py) (CSCA RSA 3072, DSC RSA 2048, P-256 chip).
One run per case.

## Cycles

`cargo run --release -p host -- execute`, which counts each step's cycles with
`env::cycle_count`:

| Step | Cycles |
| --- | --- |
| Startup and reading the inputs | 8,671 |
| Parsing and keys | 15,574 |
| DSC signature (RSA 3072) | 65,081 |
| SOD signature (RSA 2048) | 53,318 |
| messageDigest and DG1 hash | 5,193 |
| Age and expiry | 1,622 |
| **Active Authentication (P-256)** | **217,412** |
| Outside the steps: cycle logging, journal and shutdown (379,900 − 366,871) | 13,029 |
| **Program total** | **379,900** |

The guest uses RISC Zero's `p256` 0.13.2 (`p256/v0.13.2-risczero.1` in
`methods/guest/Cargo.lock`), which accelerates the curve arithmetic. Even so, the P-256
signature costs more than both RSA signatures together (65,081 + 53,318 = 118,399). Before,
the program used 161,167 cycles and fit in a 2^18 = 262,144 segment; now it does not.

## Mac

Apple Silicon, `cargo run --release -p host -- succinct`. Without the check: 31.72 s, one
2^18 segment (measured at commit `5f6cb3d`).

| Segment | Segments | Time | Peak RAM | Receipt | Verification |
| --- | --- | --- | --- | --- | --- |
| 2^19 | 1 | 59.52 s | 2.62 GB | 223,778 B | 12.52 ms |
| 2^18 | 3 | 97.98 s | 2.34 GB | 223,778 B | 12.60 ms |

Peak RAM from `/usr/bin/time -l` (maximum resident set size, in bytes): 2,624,421,888 and
2,343,419,904.

## Galaxy S25

With [the measurement script](../../prover/scripts/android-measure.sh), over `adb`, one run
after another in the table's order. `MemTotal` 11,380,220 kB; before starting,
`MemAvailable` was 3,793,476 kB. Peak RAM = `VmHWM` × 1,024.

| Segment | Segments | Time | Peak RAM | Receipt | Verification |
| --- | --- | --- | --- | --- | --- |
| 2^19 | 1 | 128.59 s | 4.41 GB (4,309,936 KiB) | 223,778 B | 21.17 ms |
| 2^18 | 3 | 195.40 s | 2.47 GB (2,412,972 KiB) | 223,778 B | 17.55 ms |
| 2^17 | 6 | 366.12 s | 1.52 GB (1,480,312 KiB) | 223,778 B | 20.33 ms |

Without the check, the same measurement gave 64.70 s and 2.34 GB with a 2^18 segment
([stage 2](stage-2-android.md)). With 2^19 the time is 128.59 / 64.70 = 1.99 times the
previous one.

## Reading

- **The receipt and the verification do not change.** Presenting the proof costs the same.
- **The segment size trades time for memory**, without touching security. The app picks the
  largest one whose peak fits in 40% of the phone's RAM (`SegmentSize`). The 40% is a
  judgment call, not a measurement. With what was measured: 12 GB → 2^19, 8 GB → 2^18, 6 GB
  or less → 2^17.
- **It was not measured on a mid-range or older phone.** With a slower processor, 2^17 may
  go well beyond the S25's 6 minutes.
- **The runs were back to back**, so the last ones may have suffered more from the phone's
  heat.
- **Android 17's per-app memory limit is still unmeasured**, and 4.41 GB is the highest peak
  the project has had.
