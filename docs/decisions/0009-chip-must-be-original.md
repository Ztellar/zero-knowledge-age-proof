# 0009 — A chip that does not prove it is original stops the proof

Date: 2026-10-29. Status: **accepted and implemented in the app** (`Gate.kt`). For the app,
it replaces point 3 of [0006](0006-active-authentication-in-proof.md).

## Context

0006 allowed making the proof without Active Authentication, with
`chip_authenticated = false`, when the ID card had no DG15 or used a key other than P-256.
But the web verifier rejects those proofs unless run with `--allow-no-chip`. For an ordinary
person that meant waiting one to three minutes for a proof that is then rejected.

## Decision

The app does not continue after reading the ID card if the chip does not prove it is
original, and says so right then, before the face match. It counts as not original when:

- the chip has no DG15;
- DG15 is not the one the SOD signs;
- the chip did not answer;
- the chip's signature is not valid.

If the signature is valid but is not ECDSA P-256 with SHA-256, the ID card is original but
the proof cannot check it. It also stops, with another message: "Este tipo de cédula todavía
no es compatible" ("This type of ID card is not supported yet").

The zkVM program does not change: it still accepts `chip_authenticated = false`. It is the
app that stops making those proofs.

## Why

The user said it on 2026-10-29: "un chip no original no debe ni pasar más allá de su
comprobación" ("a non-original chip should not even get past its check"). Also, no proof
without the chip passes the web verifier by default.

## Rejected

- **Making it anyway and warning**, as before. The person waits minutes for a proof the
  verifier rejects.

## What would invalidate it

- A real Chilean ID card using Active Authentication with RSA or another curve. Today those
  ID cards are left out; the proof's program would have to support them.
- A verifier wanting to accept proofs without the chip. Then the app would need to know what
  each verifier requires before making the proof.
