# 0007 — Face match

Date: 2026-10-29. Status: **accepted: B, starting from ZKPassport's code (E)**. Implemented
on 2026-10-29 and tested with the real ID card on the S25: the face matched, the liveness
check passed, and then the proof was made. Follows
[0006](0006-active-authentication-in-proof.md).

## Context

With 0006, the proof shows that the ID card's chip was next to the phone, but not that its
owner used it: whoever holds someone else's ID card can make that person's proof. ZKPassport
compares the face of whoever uses the app with the chip's photo, with a liveness check and
device attestation ([plan](../plan.md#what-zkpassport-already-solves)).

The photo is in DG2: ICAO 9303 stores it there, usually as JPEG 2000. Its hash is in the
SOD, so the photo is as authentic as DG1.

The real ID card used in the tests carries DG2, and the app shows it as `image/jp2` (JPEG
2000).

### What has to be solved

1. **Who believes the result?** The face of whoever uses the app comes from the camera, and
   neither the Civil Registry nor the chip signs that image. Nothing stops a modified app from
   feeding the comparator DG2's own photo as if it came from the camera.
2. **Where does it run?** On the phone, offline, like everything else.
3. **With which model?** It must have open code and weights, under a license that allows
   publishing it.

## Options

### A. Inside the proof

The RISC Zero program would read DG2, check its hash against the SOD, and run the
recognition network on the chip's photo and on the camera's.

- **It adds no security.** The camera photo is a private input nobody signs, so a modified
  app passes DG2 twice and the result is always "match".
- **Its cost is not viable.** MobileFaceNet does about 221 million multiply-adds per image
  (Chen et al., 2018). Assuming 10 cycles per operation in the zkVM, which has no
  floating-point unit (an unmeasured assumption), that is 2 × 221 × 10⁶ × 10 ≈ 4.4 × 10⁹
  cycles. That is 4.4 × 10⁹ / 379,900 ≈ 11,600 times the current program, which already
  takes 92.5 s on the S25: more than a day. Decoding JPEG 2000 comes on top.

### B. In the app, outside the proof, as help for the honest user

The app compares the camera with DG2 before making the proof and refuses to make it if they
do not match. The result does not enter the `Claim` and the verifier receives nothing new.

- **It protects against the casual case:** someone who takes another person's ID card and
  uses the published app. It does not protect against someone who builds the app without
  that check, because the code is open.
- **It promises the verifier nothing**, and is documented that way. It says the same as
  today.
- **It costs:** reading DG2, decoding JPEG 2000 on Android, a face detector and a recognition
  model inside the APK, which is already 137 MB.

### C. In the app, with device attestation

Like B, but the result is signed with a key that Android Key Attestation or Play Integrity
tie to the official, unmodified app. The verifier checks that attestation.

- **It is the only option that gives the verifier a guarantee about the face**, and it is
  what ZKPassport does.
- **It contradicts the project.** Whoever builds the open code no longer has a valid app,
  and trust moves to Google and to the signature of whoever publishes the app. Attestation
  also uses ECDSA or RSA, which are not post-quantum.
- **It needs a liveness check**, otherwise showing the camera a photo of the ID card is
  enough. A serious liveness check, resistant to masks and videos, is a product in itself.

### D. Do not match the face

Documented as a limit: the proof shows that the chip was present, not that its owner used
it.

### E. Reuse ZKPassport's implementation

ZKPassport published its app at [mobile-app](https://github.com/zkpassport/mobile-app)
(Apache-2.0, commit `c52f5ef`, a single push on 2026-07-17, so it may not be the version in
the stores). What was reviewed:

- **The code is reusable.** The
  [`modules/facematch/rust`](https://github.com/zkpassport/mobile-app/tree/main/modules/facematch/rust)
  module is Rust with [`ort`](https://crates.io/crates/ort) (ONNX Runtime) and does detection
  (640 × 640 input), 5-point alignment to 112 × 112, and the embedding. It also computes the
  head pose in `pose.rs`. The liveness check asks the user to turn the head in the directions
  the screen shows (`LIVENESS_SCHEDULE` in `FaceMatch.tsx`). Its license is compatible with
  this project's, as long as attribution is kept.
- **The models are not in the repo.** [Its download script](https://github.com/zkpassport/mobile-app/blob/main/scripts/download-facematch-models.sh)
  fetches them from `cdn.zkpassport.id`: `scrfd_2.5g_bnkps.ort` and `arcface.ort`. SCRFD 2.5G
  is an InsightFace detector, whose models are for non-commercial research only. The origin
  of `arcface.ort` is not stated; by its name and format it is probably also InsightFace's
  (unverified). Using them here would mean downloading them separately under that
  restriction, as ZKPassport does, and they could not be published under Apache-2.0.
- **The verifier's guarantee comes from attestation**, not from the match:
  [`modules/app-attest-module`](https://github.com/zkpassport/mobile-app/tree/main/modules/app-attest-module)
  uses Play Integrity on Android and App Attest on iOS. Without it, reusing their code leaves
  this project at option B.

So E is a way of doing B, not a separate option: their code with YuNet and SFace instead of
the InsightFace models. Whether the preprocessing matches needs checking, because SFace also
uses 112 × 112 aligned faces, but may normalize differently (unverified).

## Open models reviewed (2026-10-29)

Licenses were read on each model's page; the training data's terms were not reviewed.

| Model | Purpose | Weights license | Note |
| --- | --- | --- | --- |
| [YuNet](https://huggingface.co/opencv/face_detection_yunet) (OpenCV Zoo) | Face detection | MIT | — |
| [SFace](https://huggingface.co/opencv/face_recognition_sface) (OpenCV Zoo) | Recognition, a MobileFaceNet variant | Apache-2.0 | Its README gives 0.9940 on its evaluation, without naming the metric |
| [AuraFace v1](https://huggingface.co/fal/AuraFace-v1) | Recognition, ArcFace-style ResNet100 | Apache-2.0 | LFW 0.9965. Heavy for a phone; size not published |
| [InsightFace](https://pypi.org/project/insightface/) (`buffalo_l`) | Detection and recognition | **Non-commercial research only** | Rejected: it cannot be published with the project |

## Chilean Law 21.719

Facial features that "permitan o confirmen la identificación única" (allow or confirm the
unique identification) of a person are biometric data (Art. 16 ter), and biometric data are
sensitive data (Art. 2 letter g). To process them, the controller must disclose the
biometric system, the purpose, the period of use, and how to exercise one's rights
(Art. 16 ter).

In B and C the match happens on the phone and nothing leaves it. Whether that leaves the
project without a data "controller", or falls under the personal activities exception
(Art. 1), is an **unverified** interpretation. Either way, the app would have to show that
information before using the camera.

Source: official text of Law 21.719, in the sources of the `compliance-cl` skill.

## What was implemented

Verified on 2026-10-29.

- **Core:** [`prover/facematch`](../../prover/facematch/src/lib.rs), in Rust with
  [`tract`](https://crates.io/crates/tract-onnx), which runs ONNX without native binaries. It
  does detection with YuNet, 5-point alignment, the SFace embedding, and the pose. It decodes
  the chip photo from JPEG or JPEG 2000 with `jpeg2k` (a Rust port of OpenJPEG). The models
  are downloaded by [`download-face-models.sh`](../../prover/scripts/download-face-models.sh),
  pinned by revision and sha256, along with their licenses.
- **In the app:**
  - it checks that DG2 matches its hash in the SOD;
  - it shows the Art. 16 ter notice before asking for the camera;
  - it compares every front-camera frame in
    [`FaceCheck.kt`](../../android/app/src/main/kotlin/cl/zkage/app/FaceCheck.kt);
  - it does not let the proof be made until the face matches.

  An ID card without a valid photo in DG2 does not continue (changed on 2026-10-29: it used
  to make the proof without matching the face; see `Gate.kt`). None of this enters the
  proof.
- **Liveness check:** look straight ahead, turn the head to the right, then to the left, and
  look straight ahead again. The order is fixed since 2026-10-30: the user tried it on the
  S25 and the screen showed a sequence the app did not enforce. Turning to the wrong side
  does not count. Frontal frames require the full similarity (0.363); turned ones, 0.15. If
  the face is lost for more than 10 frames, the turns restart. It stops a photo; it does not
  stop a video or a mask. At the end, the screen shows "¡Bien!" ("Good!") with all four steps
  checked for 0.9 s, then "Comprobando que eres tú…" ("Checking it is you…") for 2 s before
  making the proof: it is only so the person sees the result, and adds no check.

Differences from ZKPassport, each one because of something measured on the S25:

| ZKPassport | Here | Why |
| --- | --- | --- |
| Up, down, left and right | Only left and right | Looking up, the similarity to the frontal photo dropped to 0.18–0.28 and the check almost never finished. Up and down add nothing against a photo |
| Turn measured from zero | From each person's own frontal pose | A frontal face does not have zero pitch (−0.18 on the test portraits) |
| Turn threshold of 0.23 | 0.15 of yaw | With YuNet's landmarks, a clear turn reached 0.17–0.26 |
| Pitch divided by the distance between the eyes | Divided by the eyes-to-mouth height | When turning, the distance between the eyes shrinks and a turn read as lifting the head |

Measured on the S25:

| What | Result |
| --- | --- |
| Time per 640 × 480 frame (detection and embedding) | Median of 65 to 96 ms over five sessions (75, 95, 67, 96 and 65 ms) |
| Full liveness check with the real ID card | 65 frames |
| Sessions recorded before the tuning, replayed with the final logic | All four complete, between frames 57 and 138 |

On the Mac, with the public-domain portraits: 0.688 between two photos of the same person,
0.590 and 0.658 with one of them reduced to the size of a chip photo (in grayscale and in JPEG
2000), and 0.10 and 0.15 between different people.

**Cost:** the debug APK went from 137 to 200 MB. SFace is 38.7 MB and the library grew
16.7 MB because of `tract` and the decoder.

**Not measured:** the false rejection and false acceptance rates. It was tested with one
person and three public portraits.

## Recommendation

**D for now, and B if it is to be shown in the demo**, never as a guarantee for the verifier.

- A is rejected: it adds no security and its cost does not fit on a phone.
- C gives the verifier a guarantee, but at the price of what makes the project different:
  open code anyone can build, and post-quantum in what depends on it.
- B works for the portfolio, with detection and recognition on the phone, as long as the app
  and the README say it is a local aid, not a proof. It is best done starting from
  ZKPassport's Rust module (E), with permissively licensed models.

Decision (2026-10-29): B, starting from ZKPassport's code (E), with YuNet and SFace. What was
required before implementing it:

1. Confirm that the real ID card carries DG2: yes, in JPEG 2000.
2. Measure how much the model and the JPEG 2000 decoder add to the APK: 63 MB, from 137 to
   200 MB.
3. Use only licensed photos in the tests, never the real ID card's photo: the tests use
   public-domain portraits ([`tests/faces`](../../prover/facematch/tests/faces/README.md)).
