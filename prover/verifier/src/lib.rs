//! Verifies an age presentation (decisions 0004 and 0005): the STARK proof the phone made
//! once, plus an ML-DSA-65 signature over this verifier's origin and challenge.
//!
//! Checks, in order:
//!
//! 1. the receipt is a valid proof of the project's guest program (its image ID);
//! 2. the CSCA in the claim is one the verifier trusts (Chile's);
//! 3. the claim's minimum age is at least the one the verifier asks for;
//! 4. the claim's date is neither in the future nor older than the verifier accepts;
//! 5. if the verifier asks for it, the chip answered Active Authentication (decision 0006);
//! 6. the device public key hashes to the one in the claim;
//! 7. the device key signed this verifier's origin and challenge.

mod chile_csca;

pub use chile_csca::CHILE_CSCAS;

use age_core::{Claim, Date};
use methods::AGE_GUEST_ID;
use ml_dsa::pkcs8::DecodePublicKey;
use ml_dsa::{EncodedSignature, MlDsa65, Signature, VerifyingKey};
use risc0_zkvm::Receipt;
use sha2::{Digest, Sha256};

/// Domain separation for the signed message, so a device signature cannot be reused for
/// anything else.
const MESSAGE_TAG: &[u8] = b"zkage-presentation-v1";

/// What the phone sends.
pub struct Presentation {
    /// The succinct receipt, bincode-encoded, as the app stores it.
    pub receipt: Vec<u8>,
    /// DER SubjectPublicKeyInfo of the phone's ML-DSA-65 key.
    pub device_key_spki: Vec<u8>,
    /// ML-DSA-65 signature of [`presentation_message`], empty context.
    pub signature: Vec<u8>,
}

/// What this verifier accepts.
pub struct Policy {
    /// SHA-256 of each trusted CSCA SubjectPublicKeyInfo.
    pub trusted_cscas: Vec<[u8; 32]>,
    pub min_age: u8,
    /// How old, in days, the proof may be.
    pub max_proof_age_days: u32,
    /// Reject proofs made from chip files without the chip's Active Authentication answer,
    /// which anyone who once read the card could make.
    pub require_chip_authentication: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Rejection {
    /// The receipt does not decode or does not verify against the project's image ID.
    Proof,
    UntrustedCsca,
    AgeTooLow,
    /// Dated after `today`.
    FutureProof,
    ExpiredProof,
    /// The policy requires Active Authentication and the proof was made without it.
    ChipNotAuthenticated,
    /// The public key is malformed, not ML-DSA-65, or not the one the proof names.
    DeviceKey,
    Signature,
}

/// The message the phone signs: tag, origin and challenge, so a signature made for one
/// verifier is useless to another (decision 0005).
pub fn presentation_message(origin: &str, challenge: &[u8; 32]) -> Vec<u8> {
    let origin = origin.as_bytes();
    let len = u16::try_from(origin.len()).expect("origin longer than 65535 bytes");
    let mut message = Vec::with_capacity(MESSAGE_TAG.len() + 3 + origin.len() + 32);
    message.extend_from_slice(MESSAGE_TAG);
    message.push(0);
    message.extend_from_slice(&len.to_be_bytes());
    message.extend_from_slice(origin);
    message.extend_from_slice(challenge);
    message
}

/// Runs every check; on success returns the proven claim.
pub fn verify(
    presentation: &Presentation,
    origin: &str,
    challenge: &[u8; 32],
    today: Date,
    policy: &Policy,
) -> Result<Claim, Rejection> {
    let receipt: Receipt = bincode::deserialize(&presentation.receipt).map_err(|_| Rejection::Proof)?;
    receipt.verify(AGE_GUEST_ID).map_err(|_| Rejection::Proof)?;
    let claim: Claim = receipt.journal.decode().map_err(|_| Rejection::Proof)?;
    check_claim(&claim, today, policy)?;

    if Sha256::digest(&presentation.device_key_spki).as_slice() != claim.device_key_sha256 {
        return Err(Rejection::DeviceKey);
    }
    let key = VerifyingKey::<MlDsa65>::from_public_key_der(&presentation.device_key_spki)
        .map_err(|_| Rejection::DeviceKey)?;
    let signature = EncodedSignature::<MlDsa65>::try_from(presentation.signature.as_slice())
        .ok()
        .and_then(|encoded| Signature::decode(&encoded))
        .ok_or(Rejection::Signature)?;
    if !key.verify_with_context(&presentation_message(origin, challenge), &[], &signature) {
        return Err(Rejection::Signature);
    }
    Ok(claim)
}

/// Checks 2 to 5: what the proven claim says against what this verifier accepts.
fn check_claim(claim: &Claim, today: Date, policy: &Policy) -> Result<(), Rejection> {
    if !policy.trusted_cscas.contains(&claim.csca_sha256) {
        return Err(Rejection::UntrustedCsca);
    }
    if claim.min_age < policy.min_age {
        return Err(Rejection::AgeTooLow);
    }
    let age = days(today) - days(claim.today);
    if age < 0 {
        return Err(Rejection::FutureProof);
    }
    if age > i64::from(policy.max_proof_age_days) {
        return Err(Rejection::ExpiredProof);
    }
    if policy.require_chip_authentication && !claim.chip_authenticated {
        return Err(Rejection::ChipNotAuthenticated);
    }
    Ok(())
}

/// Days since 1970-01-01 in the proleptic Gregorian calendar (Howard Hinnant's
/// `days_from_civil`).
fn days(date: Date) -> i64 {
    let (month, day) = (i64::from(date.month), i64::from(date.day));
    let year = i64::from(date.year) - i64::from(month <= 2);
    let era = year.div_euclid(400);
    let year_of_era = year - era * 400;
    let day_of_year = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(year: u16, month: u8, day: u8) -> Date {
        Date { year, month, day }
    }

    #[test]
    fn day_count() {
        assert_eq!(days(d(1970, 1, 1)), 0);
        assert_eq!(days(d(2000, 3, 1)) - days(d(2000, 2, 28)), 2);
        assert_eq!(days(d(2027, 10, 26)) - days(d(2026, 10, 26)), 365);
        assert_eq!(days(d(2028, 10, 26)) - days(d(2027, 10, 26)), 366);
    }

    #[test]
    fn message_binds_origin_and_challenge() {
        let challenge = [7; 32];
        let message = presentation_message("https://a.example", &challenge);
        assert!(message.starts_with(b"zkage-presentation-v1\0\0\x11https://a.example"));
        assert!(message.ends_with(&challenge));
        assert_ne!(message, presentation_message("https://b.example", &challenge));
    }

    #[test]
    fn chip_authentication_policy() {
        let mut claim = Claim {
            csca_sha256: [1; 32],
            today: d(2026, 10, 26),
            min_age: 18,
            device_key_sha256: [2; 32],
            chip_authenticated: false,
        };
        let mut policy =
            Policy { trusted_cscas: vec![[1; 32]], min_age: 18, max_proof_age_days: 365, require_chip_authentication: true };
        assert_eq!(check_claim(&claim, d(2026, 10, 26), &policy), Err(Rejection::ChipNotAuthenticated));
        claim.chip_authenticated = true;
        assert_eq!(check_claim(&claim, d(2026, 10, 26), &policy), Ok(()));
        claim.chip_authenticated = false;
        policy.require_chip_authentication = false;
        assert_eq!(check_claim(&claim, d(2026, 10, 26), &policy), Ok(()));
    }

    /// The app builds the same bytes (`PresentationTest` in Kotlin checks this vector).
    #[test]
    fn message_vector() {
        let message = presentation_message("https://192.168.1.5:8443", &[7; 32]);
        let hex: String = message.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, include_str!("../tests/message-vector.hex").trim());
    }
}
