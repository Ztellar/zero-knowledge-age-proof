# 0008 — Real use, and whether it is worth continuing

Date: 2026-10-29. Status: **option B accepted** on 2026-10-30, when the user asked to start
with an npm package so other services can verify the proof
([0010](0010-verifier-package-npm.md)). Partly reopens
[0003](0003-android-app-post-quantum-proof.md).

## Context

0003 left the project as a portfolio piece because "as a business it does not compete" with
ZKPassport. Two things have changed since:

- **The first version works.** It reads the real ID card, proves with Active Authentication
  in 92.5 s on the S25, and the web verifier accepted it. 0003 said the rest "comes back if
  the first version works".
- **The user asks whether it can serve real use.** The accessible redesign of 2026-10-29
  aims at the general public.

Two questions need answering: whether this can really be useful, and whether it is worth
continuing.

## Can it really be useful?

**Technically, yes.** The whole chain already works with a real ID card: reading, the Civil
Registry's signature, original chip, face, proof and verifier. What is missing for anyone to
use it is not cryptography:

| Missing | Why it matters | How we would know |
| --- | --- | --- |
| **Someone who verifies** | Without a shop or site that accepts the proof, the app is useless to anyone | A concrete verifier willing to try it |
| **Ordinary phones** | `SegmentSize` uses 40% of RAM. With 6 GB (a 2.4 GB budget) it lands on 2^17: 366 s measured on the S25. On a slower CPU it would be more; not measured | Measure it on a mid-range phone |
| **iPhone** | There is only Android today | — |
| **State keys from an official source** | The CSCAs come from ZKPassport's registry (`ChileCsca.kt`). Chile has been an ICAO PKD participant since 19/07/2024, and the PKD downloads without an account by accepting terms that **forbid republishing** its content without written ICAO permission (checked on 2026-10-29). No Civil Registry publication was found. Compared with the ICAO master list, the app's 4 keys are the official ones, but **a fifth one, from 2026, is missing** ([details](../measurements/chile-key-sizes.md)) | Ask ICAO for permission, or request the CSCAs from the Civil Registry under the Transparency Law |
| **Independent security review** | Nobody outside the project has reviewed the proof's program | — |
| **Linkability** | Two verifiers can tell it is the same proof ([0004](0004-prove-once-present-many.md)) | — |

ZKPassport, Self and the European Commission's experimental library
(`av-lib-android-zkp-age-icao`) already do this with elliptic curves. What this project does
differently is the proof based only on hash functions. That matters once states sign
documents with post-quantum algorithms, and ICAO has only started working on it.

## Options

### A. Finish and close

Finish the redesign, publish the result and an article, and leave the project as a portfolio
piece.

- Costs: what is already approved.
- Breaks when: a real verifier or an institution asks to use it.

### B. An open piece for others (recommended)

Finish the redesign and turn what is distinctive into something others can reuse:

- a specification of the verification protocol, with test vectors;
- the post-quantum proof as a library;
- an end-to-end demo with a test ID card signed with ML-DSA.

- Costs: weeks of our own work, no spending.
- Breaks when: nobody adopts the specification or the library, or ICAO's post-quantum
  migration recedes so far that nobody needs it yet.

### C. A public-use product

Play Store, support, iPhone, a security review, and compliance as a data controller under
Law 21.719. Plus a pilot with a real verifier in Chile.

- Costs: months and money (audit, developer accounts, support). It competes with products
  that already exist and have teams.
- Breaks when: no verifier willing to accept it shows up.

There is a fourth option I do not propose: competing commercially with ZKPassport. 0003
already rejected it, and the only thing that changed is that the version works, not the
market.

## Recommendation

**B.** In the project's terms: the app already shows that the idea works with a real ID card.
What makes it an advance is not having more users, but that others can reuse the
post-quantum part, and that does not depend on convincing any shop. The accessible redesign
still counts as a demo anyone can try.

C is only justified with a concrete verifier. Its trigger is below.

## What would invalidate it

- **A real verifier commits to using it** (a shop, an event, an institution). Then C is the
  better fit, starting with a pilot.
- **On a mid-range phone the proof takes more than 10 minutes or crashes for lack of
  memory.** Then public use is not viable with this prover, whatever the option, and A wins
  until the prover changes.
- **ZKPassport, Self or the EU publish a post-quantum proof for chip documents.** B's
  difference disappears and A wins.

## Cost of being wrong

Low and reversible: everything is open source and nothing prevents moving from B to C. The
real risk is spending months on C without a verifier, and it would show because there would
be nobody to run the pilot with.
