# 0002 — Our own phone registry, no blockchain, yearly revalidation

Date: 2026-10-22. Status: on hold since [decision 0003](0003-android-app-post-quantum-proof.md). **Not implemented.** Complements
[decision 0001](0001-credential-without-personal-data.md).

## Decision

- The "one ID card, one phone" registry lives on our own server, not on a blockchain.
- The per-card code is derived with an OPRF: the server never sees the ID card, and the code
  cannot be computed without both the server's secret key and the physical ID card.
- Changing phones: with the old phone at hand, a transfer code; without it, tapping the ID
  card again plus a 7-day wait during which the old phone can reject the change.
- The credential is revalidated every 12 months, or earlier if the ID card expires.

## Why

- Stopping an ID card from being on two phones requires storing something outside the new
  phone: the new phone has no way of knowing the old one exists, and the ID card's chip
  cannot be written. The minimum is a per-card code and the active phone's key.
- Blockchain rejected. What is published there is permanent, copyable by anyone and cannot
  be erased: if the code ever becomes computable from an ID card, the damage cannot be
  undone, and it clashes with the right to erasure under Law 21.719. Transactions also leave
  metadata (time, paying wallet).
- Our own server allows erasing and correcting. It only decides which phone is active: it
  cannot make anyone pass as an adult, because the chip's signature proves that.

## Fit with Chilean Law 21.719

Reviewed on 2026-10-22 against the text published in the Diario Oficial
(`ley-21719-texto.txt` from the `compliance-cl` skill). This is not legal advice.

- **The code counts as pseudonymized personal data, not anonymized.** Anonymization requires
  an "irreversible" procedure that destroys the link (art. 2 letter k). Here the link exists
  on purpose: with the server's key and the ID card the code is recomputed, which is exactly
  how duplicates are detected. It fits pseudonymization (art. 2 letter l), where the
  additional information is kept separately ("figure por separado") and protected. The
  phone's public key and the associated dates are treated the same way.
- **Obligations for the registry** (we are data controllers):
  - lawful basis: consent at registration (art. 12);
  - informing what is stored and why (art. 14 ter);
  - proportionality and limited retention (art. 3 letter c): erase the code when the
    credential expires without renewal;
  - security (art. 14 quinquies, which names pseudonymization as a measure) and data
    protection by design (art. 14 quáter): the design already aims there;
  - reporting breaches (art. 14 sexies);
  - data subject rights, in particular erasure: the person taps the ID card in the app, the
    code is recomputed and erased.
- **Impact assessment:** mandatory for "tratamiento masivo de datos o a gran escala"
  (massive or large-scale processing, art. 15 ter letter b). If it targets all of Chile, do
  it before launch.
- **Derived design rule:** register the code only after the age proof comes out positive.
  That way the registry never holds codes of minors.
- **Not settled by the text:** whether we are responsible for what the app processes inside
  the phone without ever receiving it (name, RUT, date of birth). Out of caution, disclose it
  in the privacy policy anyway. `[verify against official source]`
