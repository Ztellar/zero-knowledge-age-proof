# Key sizes of Chilean ID cards

Date: 2026-10-26. Purpose: make the test ID card use the same key sizes as real ID cards,
and know which sizes the circuit has to support.

## Sources and what they say

| Source | What it carries | Chile |
| --- | --- | --- |
| ZKPassport registry, `certificates.json` ([URL](https://raw.githubusercontent.com/zkpassport/zkpassport-packages/HEAD/packages/registry-explorer/public/assets/certificates/certificates.json), downloaded on 2026-10-22, sha256 `fc5ce6fe…f321e2`) | CSCAs: modulus, exponent, validity | 4 RSA CSCAs with SHA-256 and exponent 65537: 2013 (3072), 2016 (3072), 2021 (3072), 2024 (4096) |
| [ZKPassport support rules](https://github.com/zkpassport/zkpassport-packages/blob/fac2812/packages/registry-sdk/src/document-support-rules.json) (commit `fac2812`) | Support level per country | ID card `id_card_support: 0` (`NOT_SUPPORTED`), passport `1`. Even so, a real ID card worked in their app |
| [Self's CSCA map](https://raw.githubusercontent.com/selfxyz/self/52dba2742b4c37a957eb5ab8ebee83fdccdcf187/registry/outputs/map_csca.json) (commit `52dba274`, 2024-12-01), taken from the ICAO PKD | Number of CSCAs per algorithm and size | 3 RSA 3072 CSCAs with SHA-256 (the 2024 one did not appear yet) |
| [Self's DSC map](https://raw.githubusercontent.com/selfxyz/self/52dba2742b4c37a957eb5ab8ebee83fdccdcf187/registry/outputs/map_dsc.json) (same commit) | Number of DSCs per algorithm and size | **Chile does not appear:** it does not publish its DSCs in the ICAO PKD |
| Civil Registry and Diario Oficial | ID card regulations | They say it follows ICAO Doc 9303; they do not publish key sizes |
| A real ID card, read with the app on 2026-10-25 | The DSC inside the SOD | RSA 2048 DSC, signed by the 2021 CSCA (RSA 3072); SOD with SHA-256 and RSA PKCS#1 v1.5; PACE access |

## Conclusion

- **The CSCAs are confirmed** by two independent sources: RSA 3072 up to 2021 and RSA 4096
  from 2024.
- **The DSC size is confirmed for one ID card only:** RSA 2048 under the 2021 CSCA. Since
  Chile does not publish its DSCs, the only way to know others is to read more ID cards. **It
  is not known** which size the DSCs under the 2024 CSCA use, that is, the new-format ID cards
  issued since December 2024.
- **The circuit has to accept RSA from 2048 to 4096 bits** in both signatures. The `rsa`
  crate already does; the tests cover 3072/2048 (default) and 4096/4096 (worst case).

## Comparison with the ICAO master list (2026-10-29)

Source: `ICAO_ML_20261013112327.ml`, downloaded by the user from icao.int (sha256
`fcb9cce9…5ab31f`). The CMS signature verifies (`openssl cms -verify`) with the "ICAO Master
List Signer", and that signer chains to the "United Nations CSCA" (sha256
`920693CD…D92E4E`). That anchor was taken from the same file and not checked against another
source.

Chile has 9 certificates in the list: 5 self-signed keys and 4 link certificates, forming an
unbroken chain 2013 → 2016 → 2021 → 2024 → 2026.

| Key | In the ICAO list | In `ChileCsca.kt` |
| --- | --- | --- |
| 2013, RSA 3072 | Yes | Yes, same modulus |
| 2016, RSA 3072 | Yes | Yes, same modulus |
| 2021, RSA 3072 | Yes | Yes, same modulus |
| 2024, RSA 4096 | Yes | Yes, same modulus |
| **2026, RSA 4096** (issued on 2026-05-05; signs documents from 2026-05-05 to 2031-05-05) | Yes | **No** |

- The app's 4 keys are the official ones.
- **The 2026 one is missing.** It is not in ZKPassport's registry either: its
  `certificates.json` still has the same sha256 `fc5ce6fe…`.
- A document whose DSC was signed by the 2026 key would be rejected as "unrecognized CSCA".
  It fails safely, but it fails. **It is not known** which documents use that key (ID cards,
  passports or both).
- The key was not copied into the repo: the master list's terms forbid republishing it
  without written ICAO permission.

## Measurement with the real sizes

Test ID card regenerated with `gen.py` defaults (CSCA 3072, DSC 2048). Mac M1 Pro, same
method as [stage 1](stage-1-desktop.md), one run per row.

| Step | 4096 / 4096 | 3072 / 2048 |
| --- | --- | --- |
| DSC signature (CSCA key) | 77,050 | 65,335 |
| SOD signature (DSC key) | 74,981 | 53,093 |
| Parsing the SOD and certificates | 21,080 | 15,704 |
| **Program total** | **198,377** | **159,368** |

| Receipt | Total cycles | Proving time | Peak RAM | Size |
| --- | --- | --- | --- | --- |
| Succinct | 262,144 | 32.7 s | 2.16 GB | 223,514 B |
| Composite | 262,144 | 26.2 s | 1.75 GB | 256,250 B |

- **Time does not drop:** 159,368 plus 47,344 of paging add up to 206,712, more than
  2^17 = 131,072, so the proof stays in a 2^18 segment.
- **Before measuring I estimated about 108,000 cycles**, assuming RSA costs the square of
  the key size. That was wrong: the 2048-bit signature costs 71% of the 4096-bit one
  (53,093 / 74,981), not 25%.
- **On the Mac RAM drops:** 2.57 → 2.16 GB for the succinct proof. The composite gave 26.2 s
  against 22.8 s before; with a single run per row, that difference may be noise.

### On the S25

Same method as [stage 2](stage-2-android.md). `MemAvailable` before starting:
2,941,132 kB. RAM in GB = `VmHWM` × 1,024.

| Receipt | Run | Proving time | Peak RAM | Verification |
| --- | --- | --- | --- | --- |
| Succinct | 1 | 58.58 s | no valid reading | 14.4 ms |
| Succinct | 2 | 64.89 s | no valid reading | 18.1 ms |
| Succinct | 3 | 57.67 s | 2.46 GB (2,406,864 kB) | — |
| Composite | 1 | 43.09 s | no valid reading | 22.5 ms |
| Composite | 2 | 41.19 s | 2.46 GB (2,405,360 kB) | — |

- **"No valid reading":** in those runs the script measured the RAM of a subshell
  (1.3–1.4 MB) instead of the prover's. It was fixed before the runs with RAM. The stage 2
  measurements used another script, which launched the prover directly, and do not have
  this problem.
- **Time:** the succinct proof ranges from 57.7 to 64.9 s, against 64.7–65.0 s with
  4096/4096. The difference is small and of the same order as the variation between runs.
- **RAM:** on the phone it does not drop (2.46 GB against 2.34–2.47 GB), although on the Mac
  it did. What dominates is the segment size (2^18), not the key size.
