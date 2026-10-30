# 0001 — A credential without personal data, bound to a phone

Date: 2026-10-22. Status: on hold since [decision 0003](0003-android-app-post-quantum-proof.md). **Not implemented.**

## Decision

After reading the ID card, the phone stores only a proof of "over 18 on date D", bound to a
non-extractable key in the phone's secure chip. It does not store the name, national ID
number (RUT), date of birth or photo, and no server receives them. Each use makes a new
proof that the site cannot link to other uses. An ID card has only one active phone, and the
credential is revalidated periodically.

## Amendment (2026-10-22): face match

At registration, the phone also reads the chip's photo and compares it with the face of the
person in front of the camera. Both images are discarded at the end and never leave the
phone. Reason: ZKPassport does it and, without it, anyone can register a borrowed ID card.

It is biometric data (art. 16 ter of Chilean Law 21.719). It requires express consent
(art. 16) and informing four things beforehand: which biometric system is used, what for,
for how long, and how to exercise one's rights. That means a consent screen before the
camera. Whether processing inside the phone makes us data controllers remains unresolved
(see decision 0002); the rule is applied anyway, out of caution.

## Why

- The requirement is that no personal data reaches third parties, including foreign
  providers: the design prevents it by construction, not by policy.
- A bare flag (`mayor_de_18 = true`) can be forged by modifying the app; with its proof and
  the phone's key, it cannot.
- Age only goes up: "over 18 on date D" stays true afterwards, so the proof does not expire
  because of age, only through revalidation or the ID card's expiry.

## Rejected

- **Querying the Civil Registry online:** it requires an agreement, the state would see
  every site visited, and it depends on their system being up. The chip's signature already
  proves the data.
- **Storing the ID card data encrypted on the phone:** there is still stored personal data.
- **Zero storage (tapping the ID card on every use):** too much friction.

## Accepted cost

"One ID card, one phone" needs a shared registry of document identifiers. It holds no
personal data, but the system stops having no central component at all. Where that registry
lives was settled in [decision 0002](0002-device-registry.md).
