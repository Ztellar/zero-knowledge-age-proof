//! The expected results of `docs/protocol.md`, section 7, through the module's request
//! format, plus the module's own limits.

use std::fs;
use std::path::PathBuf;

use age_core::Date;
use age_verifier_wasm::{check, Request, Status, MAX_PROOF_AGE_DAYS, RESULT_LEN};
use sha2::{Digest, Sha256};

const ORIGIN: &str = "https://verifier.example";
const CHALLENGE: [u8; 32] = [0xc4; 32];
const PROVED_ON: Date = Date { year: 2026, month: 10, day: 22 };

fn fixture(path: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures").join(path);
    fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

fn test_csca() -> [u8; 32] {
    Sha256::digest(fixture("out/csca_spki.der")).into()
}

/// The vector's inputs, with the test ID card's CSCA trusted.
fn request(body: &[u8]) -> Request<'_> {
    Request {
        body,
        origin: ORIGIN,
        challenge: CHALLENGE,
        today: PROVED_ON,
        min_age: 18,
        max_proof_age_days: MAX_PROOF_AGE_DAYS,
        extra_cscas: vec![test_csca()],
    }
}

fn status(request: &Request) -> Status {
    check(&request.encode()).status
}

#[test]
fn accepts_the_vector() {
    let body = fixture("vectors/presentation-body.bin");
    let verdict = check(&request(&body).encode());
    assert_eq!(verdict.status, Status::Accepted);
    let claim = verdict.claim.clone().unwrap();
    assert_eq!((claim.min_age, claim.today, claim.chip_authenticated), (18, PROVED_ON, true));
    assert_eq!(claim.csca_sha256, test_csca());

    let encoded = verdict.encode();
    assert_eq!(encoded.len(), RESULT_LEN);
    assert_eq!(encoded[..6], [18, 0xea, 0x07, 10, 22, 1]);
    assert_eq!(encoded[6..], test_csca());
}

#[test]
fn expected_results_of_the_protocol() {
    let body = fixture("vectors/presentation-body.bin");
    let at = |year, month, day| Request { today: Date { year, month, day }, ..request(&body) };
    assert_eq!(status(&at(2027, 10, 22)), Status::Accepted);
    assert_eq!(status(&at(2027, 10, 23)), Status::ExpiredProof);
    assert_eq!(status(&at(2026, 10, 21)), Status::FutureProof);
    assert_eq!(status(&Request { min_age: 21, ..request(&body) }), Status::AgeTooLow);
    // Chile's CSCAs alone do not include the test ID card's.
    assert_eq!(status(&Request { extra_cscas: vec![], ..request(&body) }), Status::UntrustedCsca);
    assert_eq!(status(&Request { origin: "https://evil.example", ..request(&body) }), Status::Signature);
    assert_eq!(status(&Request { challenge: [0; 32], ..request(&body) }), Status::Signature);

    let mut tampered = body.clone();
    let receipt_len = u32::from_le_bytes(body[..4].try_into().unwrap()) as usize;
    tampered[4 + receipt_len / 2] ^= 1;
    assert_eq!(status(&request(&tampered)), Status::Proof);
}

#[test]
fn rejects_malformed_bodies() {
    let body = fixture("vectors/presentation-body.bin");
    assert_eq!(status(&request(&body[..body.len() - 1])), Status::MalformedBody);
    assert_eq!(status(&request(&[body.as_slice(), &[0]].concat())), Status::MalformedBody);
    assert_eq!(status(&request(&[])), Status::MalformedBody);
}

/// The host can ask for more than decisions 0009 and 0010, never for less.
#[test]
fn rejects_a_relaxed_policy() {
    let body = fixture("vectors/presentation-body.bin");
    assert_eq!(status(&Request { min_age: 17, ..request(&body) }), Status::BadRequest);
    assert_eq!(status(&Request { max_proof_age_days: MAX_PROOF_AGE_DAYS + 1, ..request(&body) }), Status::BadRequest);
    // A shorter window is fine: the proof is from the same day.
    assert_eq!(status(&Request { max_proof_age_days: 0, ..request(&body) }), Status::Accepted);
}

#[test]
fn rejects_malformed_requests() {
    let body = fixture("vectors/presentation-body.bin");
    let encoded = request(&body).encode();
    assert_eq!(check(&encoded[..encoded.len() - 1]).status, Status::BadRequest);
    assert_eq!(check(&[encoded.as_slice(), &[0]].concat()).status, Status::BadRequest);
    assert_eq!(check(&[]).status, Status::BadRequest);
    // The first byte of the origin, which is not valid UTF-8 on its own.
    let mut bad = encoded.clone();
    bad[4 + body.len() + 4] = 0xff;
    assert_eq!(check(&bad).status, Status::BadRequest);
}

#[test]
fn request_round_trip() {
    let body = fixture("vectors/presentation-body.bin");
    let original = Request { extra_cscas: vec![[1; 32], [2; 32]], ..request(&body) };
    let encoded = original.encode();
    assert_eq!(Request::parse(&encoded), Some(original));
}

/// The numbers are the module's interface; changing them breaks the npm package.
#[test]
fn status_numbers_and_codes_are_stable() {
    let table: Vec<(u32, &str)> = Status::ALL.iter().map(|&s| (s as u32, s.code())).collect();
    assert_eq!(
        table,
        [
            (0, "accepted"),
            (1, "proof"),
            (2, "untrusted_csca"),
            (3, "age_too_low"),
            (4, "future_proof"),
            (5, "expired_proof"),
            (6, "chip_not_authenticated"),
            (7, "device_key"),
            (8, "signature"),
            (9, "malformed_body"),
            (10, "bad_request"),
        ]
    );
}
