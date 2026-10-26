//! Stage 1 measurement: prove the mock card's age claim and report time and size.
//!
//!     [SEGMENT_PO2=18] cargo run --release -p host -- [execute|composite|succinct]
//!
//! On Android, build with `--features prove` (no `r0vm` there) and point `FIXTURES_DIR` at
//! the pushed fixtures.
//!
//! With `RECEIPT_OUT=<path>`, `succinct` also writes the receipt there (bincode), bound to
//! the demo device key below: the verifier's tests use it.
//!
//! `execute` only runs the guest (cycle count, no proof). `composite` is RISC Zero's
//! default receipt, one STARK per segment. `succinct` compresses it into a single STARK.
//! Neither is wrapped in Groth16, which would not be post-quantum.

use std::path::PathBuf;
use std::time::Instant;
use std::{env, fs};

use age_core::{Claim, Date, Inputs};
use methods::{AGE_GUEST_ELF, AGE_GUEST_ID};
use ml_dsa::pkcs8::EncodePublicKey;
use ml_dsa::{Keypair, MlDsa65, SigningKey};
use p256::ecdsa::signature::Signer;
use p256::pkcs8::DecodePrivateKey;
use sha2::{Digest, Sha256};
use risc0_zkvm::{default_executor, default_prover, ExecutorEnv, ProverOpts};

fn fixture(name: &str) -> Vec<u8> {
    let dir = env::var_os("FIXTURES_DIR").map(PathBuf::from).unwrap_or_else(|| {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/out")
    });
    let path = dir.join(name);
    fs::read(&path).unwrap_or_else(|e| panic!("{}: {e} (run fixtures/gen.py first)", path.display()))
}

/// Seed of the ML-DSA-65 key that stands in for a phone's. Public on purpose: test data only.
pub const DEMO_DEVICE_SEED: [u8; 32] = [0x5e; 32];

fn main() {
    let mode = env::args().nth(1).unwrap_or_else(|| "succinct".into());
    let device_key_sha256: [u8; 32] = Sha256::digest(demo_device_spki()).into();
    // The mock chip answers Active Authentication for the demo key, as a real chip would.
    let chip = p256::ecdsa::SigningKey::from_pkcs8_der(&fixture("chip_aa_key.der")).unwrap();
    let aa_signature: p256::ecdsa::Signature = chip.sign(&device_key_sha256[..8]);
    let inputs = Inputs {
        csca_spki: fixture("csca_spki.der"),
        sod_der: fixture("sod.der"),
        dg1: fixture("dg1.bin"),
        dg15: fixture("dg15.bin"),
        aa_signature: aa_signature.to_bytes().to_vec(),
        today: Date { year: 2026, month: 10, day: 22 },
        min_age: 18,
        device_key_sha256,
    };
    // Smaller segments trade proving time for peak memory, which matters on phones.
    let mut builder = ExecutorEnv::builder();
    if let Ok(po2) = env::var("SEGMENT_PO2") {
        builder.segment_limit_po2(po2.parse().expect("SEGMENT_PO2 must be a number"));
    }
    let env = builder.write_slice(&inputs.to_bytes()).build().unwrap();

    if mode == "execute" {
        let start = Instant::now();
        let session = default_executor().execute(env, AGE_GUEST_ELF).unwrap();
        println!("cycles (user): {}", session.cycles());
        println!("segments: {}", session.segments.len());
        println!("execute time: {:.2?}", start.elapsed());
        return;
    }

    let opts = match mode.as_str() {
        "composite" => ProverOpts::composite(),
        "succinct" => ProverOpts::succinct(),
        other => panic!("unknown mode {other}"),
    };
    let start = Instant::now();
    let info = default_prover().prove_with_opts(env, AGE_GUEST_ELF, &opts).unwrap();
    let prove_time = start.elapsed();

    let receipt = info.receipt;
    let start = Instant::now();
    receipt.verify(AGE_GUEST_ID).unwrap();
    let verify_time = start.elapsed();

    let claim: Claim = receipt.journal.decode().unwrap();
    let bytes = bincode::serialize(&receipt).unwrap();
    println!("mode: {mode}");
    println!("total cycles: {}", info.stats.total_cycles);
    println!("user cycles: {}", info.stats.user_cycles);
    println!("paging cycles: {}", info.stats.paging_cycles);
    println!("reserved cycles: {}", info.stats.reserved_cycles);
    println!("segments: {}", info.stats.segments);
    println!("prove time: {prove_time:.2?}");
    println!("verify time: {verify_time:.2?}");
    println!("receipt size: {} bytes", bytes.len());
    println!("claim: {claim:?}");
    if let ("succinct", Some(path)) = (mode.as_str(), env::var_os("RECEIPT_OUT")) {
        fs::write(&path, &bytes).unwrap();
        println!("receipt written to {}", PathBuf::from(path).display());
    }
}

fn demo_device_spki() -> Vec<u8> {
    let key = SigningKey::<MlDsa65>::from_seed(&DEMO_DEVICE_SEED.into());
    key.verifying_key().to_public_key_der().unwrap().into_vec()
}
