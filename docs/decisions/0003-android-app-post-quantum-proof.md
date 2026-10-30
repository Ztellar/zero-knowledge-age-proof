# 0003 — Open-source Android app with a post-quantum proof

Date: 2026-10-22. Status: accepted. **Not implemented.** Puts decisions
[0001](0001-credential-without-personal-data.md) and [0002](0002-device-registry.md) on hold.

## Decision

The project becomes a portfolio project, open source and meant for others to learn from. The
first version is an Android app that:

1. reads a Chilean ID card over NFC;
2. checks the Civil Registry's signature against Chile's public certificates;
3. makes, on the phone, a zero-knowledge proof of "over 18" with a post-quantum proof system
   (a STARK, based only on hash functions).

## Why

- As a business it does not compete: ZKPassport already solves what 0001 and 0002 designed,
  and better in several respects (see "What ZKPassport already solves" in
  [the plan](../plan.md)).
- As a portfolio piece it does: applied cryptography on the Chilean ID card is uncommon.
- ZKPassport uses UltraHonk, based on elliptic curves and not post-quantum. A STARK version
  is a real and explainable difference.

## Acknowledged limit

The system **cannot be post-quantum end to end**: the Civil Registry's signature is RSA and
the state chooses it. A quantum computer able to break RSA could forge ID cards. The project
makes post-quantum what it controls and documents what it does not.

## Out of the first version

Face match, phone registry, OPRF, phone change and revalidation (0001 and 0002). They come
back if the first version works.
