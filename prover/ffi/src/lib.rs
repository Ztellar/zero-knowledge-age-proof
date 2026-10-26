//! JNI entry point for the Android app: check the chip data, prove the age claim and
//! verify the receipt, all on the phone.
//!
//! Kotlin side: `cl.zkage.app.NativeProver.prove`.

use age_core::{verify, Claim, Date, Inputs};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use methods::{AGE_GUEST_ELF, AGE_GUEST_ID};
use risc0_zkvm::{default_prover, ExecutorEnv, ProverOpts};

/// Result layout, little-endian:
/// `u32 receipt_len | receipt (bincode) | csca_sha256 [32] | u16 year | u8 month | u8 day | u8 min_age`.
///
/// Throws `IllegalArgumentException` with the `age_core::Error` name when the chip data
/// does not support the claim (checked natively first, which takes milliseconds), and
/// `IllegalStateException` if proving or verifying fails.
#[no_mangle]
pub extern "system" fn Java_cl_zkage_app_NativeProver_prove<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    csca_spki: JByteArray<'local>,
    sod: JByteArray<'local>,
    dg1: JByteArray<'local>,
    year: jint,
    month: jint,
    day: jint,
    min_age: jint,
) -> jbyteArray {
    let result = (|| -> Result<Vec<u8>, (&'static str, String)> {
        let bytes = |env: &mut JNIEnv, a: &JByteArray| {
            env.convert_byte_array(a).map_err(|e| ("java/lang/IllegalStateException", e.to_string()))
        };
        let inputs = Inputs {
            csca_spki: bytes(&mut env, &csca_spki)?,
            sod_der: bytes(&mut env, &sod)?,
            dg1: bytes(&mut env, &dg1)?,
            today: Date { year: year as u16, month: month as u8, day: day as u8 },
            min_age: min_age as u8,
        };
        verify(&inputs).map_err(|e| ("java/lang/IllegalArgumentException", format!("{e:?}")))?;
        prove(&inputs).map_err(|e| ("java/lang/IllegalStateException", e))
    })();

    match result.and_then(|out| {
        env.byte_array_from_slice(&out).map_err(|e| ("java/lang/IllegalStateException", e.to_string()))
    }) {
        Ok(array) => array.into_raw(),
        Err((class, message)) => {
            let _ = env.throw_new(class, message);
            std::ptr::null_mut()
        }
    }
}

fn prove(inputs: &Inputs) -> Result<Vec<u8>, String> {
    let env = ExecutorEnv::builder().write_slice(&inputs.to_bytes()).build().map_err(|e| e.to_string())?;
    // Succinct, never Groth16: a Groth16 wrapper would not be post-quantum.
    let info = default_prover()
        .prove_with_opts(env, AGE_GUEST_ELF, &ProverOpts::succinct())
        .map_err(|e| e.to_string())?;
    let receipt = info.receipt;
    receipt.verify(AGE_GUEST_ID).map_err(|e| e.to_string())?;
    let claim: Claim = receipt.journal.decode().map_err(|e| e.to_string())?;

    let encoded = bincode::serialize(&receipt).map_err(|e| e.to_string())?;
    let mut out = Vec::with_capacity(encoded.len() + 41);
    out.extend_from_slice(&(encoded.len() as u32).to_le_bytes());
    out.extend_from_slice(&encoded);
    out.extend_from_slice(&claim.csca_sha256);
    out.extend_from_slice(&claim.today.year.to_le_bytes());
    out.extend_from_slice(&[claim.today.month, claim.today.day, claim.min_age]);
    Ok(out)
}
