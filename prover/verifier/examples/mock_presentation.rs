//! Writes a presentation body for the web verifier to stdout, from the mock card's receipt
//! and the host's demo device key: the phone's part, for testing the server without one.
//!
//!     cargo run --release -p age-verifier --example mock_presentation -- <origin> <challenge hex> > body.bin

use std::io::Write;

use age_verifier::presentation_message;
use ml_dsa::pkcs8::EncodePublicKey;
use ml_dsa::{Keypair, MlDsa65, Signer, SigningKey};

/// The host's `DEMO_DEVICE_SEED`.
const DEMO_DEVICE_SEED: [u8; 32] = [0x5e; 32];

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let [_, origin, challenge] = args.as_slice() else {
        panic!("usage: mock_presentation <origin> <challenge hex>");
    };
    let challenge: [u8; 32] = (0..32)
        .map(|i| u8::from_str_radix(&challenge[2 * i..2 * i + 2], 16).expect("challenge must be hex"))
        .collect::<Vec<_>>()
        .try_into()
        .expect("challenge must be 32 bytes");

    let key = SigningKey::<MlDsa65>::from_seed(&DEMO_DEVICE_SEED.into());
    let receipt = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/../fixtures/out/receipt.bin")).unwrap();
    let spki = key.verifying_key().to_public_key_der().unwrap().into_vec();
    let signature = key.sign(&presentation_message(origin, &challenge)).encode().to_vec();

    let mut body = Vec::new();
    for field in [&receipt, &spki, &signature] {
        body.extend_from_slice(&(field.len() as u32).to_le_bytes());
        body.extend_from_slice(field);
    }
    std::io::stdout().write_all(&body).unwrap();
}
