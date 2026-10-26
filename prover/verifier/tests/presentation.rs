//! Against the mock card's receipt, written by
//! `RECEIPT_OUT=fixtures/out/receipt.bin cargo run --release -p host -- succinct`.
//! It must be regenerated whenever the guest changes, or every presentation fails the image
//! ID check.

use std::fs;
use std::path::PathBuf;

use age_core::Date;
use age_verifier::{presentation_message, verify, Policy, Presentation, Rejection};
use ml_dsa::pkcs8::EncodePublicKey;
use ml_dsa::{Keypair, MlDsa65, Signer, SigningKey};
use sha2::{Digest, Sha256};

const ORIGIN: &str = "https://verifier.example";
const CHALLENGE: [u8; 32] = [0xc4; 32];
/// The host's `DEMO_DEVICE_SEED`.
const DEMO_DEVICE_SEED: [u8; 32] = [0x5e; 32];
/// The date the host proves for.
const PROVED_ON: Date = Date { year: 2026, month: 10, day: 22 };

fn fixture(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/out").join(name);
    fs::read(&path).unwrap_or_else(|e| panic!("{}: {e} (see this file's header)", path.display()))
}

fn key(seed: [u8; 32]) -> SigningKey<MlDsa65> {
    SigningKey::from_seed(&seed.into())
}

fn spki(key: &SigningKey<MlDsa65>) -> Vec<u8> {
    key.verifying_key().to_public_key_der().unwrap().into_vec()
}

fn sign(key: &SigningKey<MlDsa65>, origin: &str, challenge: &[u8; 32]) -> Vec<u8> {
    // Deterministic ML-DSA with an empty context, which is all `Signer` offers here.
    key.sign(&presentation_message(origin, challenge)).encode().to_vec()
}

fn presentation() -> Presentation {
    let device = key(DEMO_DEVICE_SEED);
    Presentation {
        receipt: fixture("receipt.bin"),
        device_key_spki: spki(&device),
        signature: sign(&device, ORIGIN, &CHALLENGE),
    }
}

fn policy() -> Policy {
    Policy {
        trusted_cscas: vec![Sha256::digest(fixture("csca_spki.der")).into()],
        min_age: 18,
        max_proof_age_days: 365,
        require_chip_authentication: true,
    }
}

fn check(p: &Presentation, policy: &Policy, today: Date) -> Result<(), Rejection> {
    verify(p, ORIGIN, &CHALLENGE, today, policy).map(|_| ())
}

#[test]
fn accepts_valid_presentation() {
    let claim = verify(&presentation(), ORIGIN, &CHALLENGE, PROVED_ON, &policy()).unwrap();
    assert_eq!((claim.min_age, claim.today), (18, PROVED_ON));
}

#[test]
fn proof_validity_window() {
    let p = presentation();
    assert_eq!(check(&p, &policy(), Date { year: 2027, month: 10, day: 22 }), Ok(()));
    assert_eq!(check(&p, &policy(), Date { year: 2027, month: 10, day: 23 }), Err(Rejection::ExpiredProof));
    assert_eq!(check(&p, &policy(), Date { year: 2026, month: 10, day: 21 }), Err(Rejection::FutureProof));
}

#[test]
fn rejects_policy_mismatches() {
    let p = presentation();
    let untrusted = Policy { trusted_cscas: vec![Sha256::digest(fixture("dsc_spki.der")).into()], ..policy() };
    assert_eq!(check(&p, &untrusted, PROVED_ON), Err(Rejection::UntrustedCsca));
    let stricter = Policy { min_age: 21, ..policy() };
    assert_eq!(check(&p, &stricter, PROVED_ON), Err(Rejection::AgeTooLow));
}

/// A relayed QR: the phone signed for another origin or challenge.
#[test]
fn rejects_signature_for_other_origin_or_challenge() {
    let device = key(DEMO_DEVICE_SEED);
    let other_origin = Presentation { signature: sign(&device, "https://evil.example", &CHALLENGE), ..presentation() };
    assert_eq!(check(&other_origin, &policy(), PROVED_ON), Err(Rejection::Signature));
    let other_challenge = Presentation { signature: sign(&device, ORIGIN, &[0; 32]), ..presentation() };
    assert_eq!(check(&other_challenge, &policy(), PROVED_ON), Err(Rejection::Signature));
    let garbage = Presentation { signature: vec![0; 10], ..presentation() };
    assert_eq!(check(&garbage, &policy(), PROVED_ON), Err(Rejection::Signature));
}

/// Someone else's phone presenting this proof with its own key.
#[test]
fn rejects_other_device_key() {
    let thief = key([0x01; 32]);
    let p = Presentation { device_key_spki: spki(&thief), signature: sign(&thief, ORIGIN, &CHALLENGE), ..presentation() };
    assert_eq!(check(&p, &policy(), PROVED_ON), Err(Rejection::DeviceKey));
}

#[test]
fn rejects_tampered_receipt() {
    let mut p = presentation();
    let middle = p.receipt.len() / 2;
    p.receipt[middle] ^= 1;
    assert_eq!(check(&p, &policy(), PROVED_ON), Err(Rejection::Proof));
    let truncated = Presentation { receipt: p.receipt[..1000].to_vec(), ..presentation() };
    assert_eq!(check(&truncated, &policy(), PROVED_ON), Err(Rejection::Proof));
}

/// The Kotlin side (`MlDsaTest.rustSeedMatches`) derives the same public key from the same
/// seed: FIPS 204 `KeyGen_internal` in both libraries.
#[test]
fn seed_derivation_matches_kotlin() {
    let seed: [u8; 32] = std::array::from_fn(|i| i as u8);
    let hash = Sha256::digest(spki(&key(seed)));
    let hex: String = hash.iter().map(|b| format!("{b:02x}")).collect();
    println!("spki sha256 for seed 00..1f: {hex}");
    assert_eq!(hex, include_str!("seed-00-1f.sha256").trim());
}
