//! End-to-end checks against the mock card produced by `fixtures/gen.py`.

use std::fs;
use std::path::PathBuf;

use age_core::{verify, Date, Error, Inputs};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::DecodePrivateKey;
use sha2::{Digest, Sha256};

fn fixture(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/out").join(name);
    fs::read(&path).unwrap_or_else(|e| panic!("{}: {e} (run fixtures/gen.py first)", path.display()))
}

/// What the mock chip answers to an Active Authentication challenge.
fn chip_signs(challenge: &[u8]) -> Vec<u8> {
    let key = SigningKey::from_pkcs8_der(&fixture("chip_aa_key.der")).unwrap();
    let signature: Signature = key.sign(challenge);
    signature.to_bytes().to_vec()
}

fn inputs() -> Inputs {
    Inputs {
        csca_spki: fixture("csca_spki.der"),
        sod_der: fixture("sod.der"),
        dg1: fixture("dg1.bin"),
        dg15: fixture("dg15.bin"),
        aa_signature: chip_signs(&[0xd0; 8]),
        today: Date { year: 2026, month: 10, day: 22 },
        min_age: 18,
        device_key_sha256: [0xd0; 32],
    }
}

#[test]
fn mock_card_proves_adult() {
    let claim = verify(&inputs()).expect("mock card should verify");
    assert_eq!(claim.min_age, 18);
    let csca: [u8; 32] = Sha256::digest(fixture("csca_spki.der")).into();
    assert_eq!(claim.csca_sha256, csca);
    assert_eq!(claim.device_key_sha256, [0xd0; 32]);
    assert!(claim.chip_authenticated);
}

#[test]
fn card_read_without_active_authentication() {
    let mut i = inputs();
    i.dg15.clear();
    i.aa_signature.clear();
    assert!(!verify(&i).expect("still a valid card").chip_authenticated);
}

#[test]
fn chip_answer_for_another_phone_is_rejected() {
    let mut i = inputs();
    // Same chip, but it signed another device key's hash.
    i.aa_signature = chip_signs(&[0xd1; 8]);
    assert_eq!(verify(&i), Err(Error::ActiveAuthentication));
    i.aa_signature[0] ^= 1;
    assert_eq!(verify(&i), Err(Error::ActiveAuthentication));
}

#[test]
fn half_an_active_authentication_is_rejected() {
    let mut i = inputs();
    i.aa_signature.clear();
    assert_eq!(verify(&i), Err(Error::ActiveAuthentication));
    let mut i = inputs();
    i.dg15.clear();
    assert_eq!(verify(&i), Err(Error::ActiveAuthentication));
}

#[test]
fn another_chips_key_is_rejected() {
    // A DG15 the SOD does not sign: a copied card with a key the attacker controls.
    let mut i = inputs();
    let last = i.dg15.len() - 1;
    i.dg15[last] ^= 1;
    assert_eq!(verify(&i), Err(Error::Dg15Hash));
}

#[test]
fn chip_wrapped_sod_is_accepted() {
    // EF.SOD as the chip returns it: application tag 0x77 around the ContentInfo.
    let mut i = inputs();
    let len = i.sod_der.len();
    let mut wrapped = vec![0x77, 0x82, (len >> 8) as u8, len as u8];
    wrapped.extend_from_slice(&i.sod_der);
    i.sod_der = wrapped;
    verify(&i).expect("wrapped SOD should verify");
}

#[test]
fn tampered_dg1_is_rejected() {
    let mut i = inputs();
    let last = i.dg1.len() - 1;
    i.dg1[last] ^= 1;
    assert_eq!(verify(&i), Err(Error::Dg1Hash));
}

#[test]
fn wrong_csca_is_rejected() {
    let mut i = inputs();
    i.csca_spki = fixture("dsc_spki.der");
    assert_eq!(verify(&i), Err(Error::DscSignature));
}

#[test]
fn underage_threshold_is_rejected() {
    let mut i = inputs();
    i.min_age = 40;
    assert_eq!(verify(&i), Err(Error::Underage));
}

#[test]
fn expired_card_is_rejected() {
    let mut i = inputs();
    i.today = Date { year: 2030, month: 1, day: 2 };
    assert_eq!(verify(&i), Err(Error::Expired));
}
