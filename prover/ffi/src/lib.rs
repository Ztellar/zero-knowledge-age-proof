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
/// `u32 receipt_len | receipt (bincode) | csca_sha256 [32] | u16 year | u8 month | u8 day | u8 min_age
/// | device_key_sha256 [32] | u8 chip_authenticated`.
///
/// `dg15` and `aa_signature` are both empty when the card is proven without Active
/// Authentication. `segment_po2` sets the zkVM segment size: smaller segments use less memory
/// and take longer, for phones with less RAM.
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
    device_key_sha256: JByteArray<'local>,
    dg15: JByteArray<'local>,
    aa_signature: JByteArray<'local>,
    segment_po2: jint,
) -> jbyteArray {
    let result = (|| -> Result<Vec<u8>, (&'static str, String)> {
        let bytes = |env: &mut JNIEnv, a: &JByteArray| {
            env.convert_byte_array(a).map_err(|e| ("java/lang/IllegalStateException", e.to_string()))
        };
        let inputs = Inputs {
            csca_spki: bytes(&mut env, &csca_spki)?,
            sod_der: bytes(&mut env, &sod)?,
            dg1: bytes(&mut env, &dg1)?,
            dg15: bytes(&mut env, &dg15)?,
            aa_signature: bytes(&mut env, &aa_signature)?,
            today: Date { year: year as u16, month: month as u8, day: day as u8 },
            min_age: min_age as u8,
            device_key_sha256: bytes(&mut env, &device_key_sha256)?
                .try_into()
                .map_err(|_| ("java/lang/IllegalArgumentException", "device key hash must be 32 bytes".into()))?,
        };
        verify(&inputs).map_err(|e| ("java/lang/IllegalArgumentException", format!("{e:?}")))?;
        prove(&inputs, segment_po2 as u32).map_err(|e| ("java/lang/IllegalStateException", e))
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

fn prove(inputs: &Inputs, segment_po2: u32) -> Result<Vec<u8>, String> {
    let env = ExecutorEnv::builder()
        .segment_limit_po2(segment_po2)
        .write_slice(&inputs.to_bytes())
        .build()
        .map_err(|e| e.to_string())?;
    // Succinct, never Groth16: a Groth16 wrapper would not be post-quantum.
    let info = default_prover()
        .prove_with_opts(env, AGE_GUEST_ELF, &ProverOpts::succinct())
        .map_err(|e| e.to_string())?;
    let receipt = info.receipt;
    receipt.verify(AGE_GUEST_ID).map_err(|e| e.to_string())?;
    let claim: Claim = receipt.journal.decode().map_err(|e| e.to_string())?;

    let encoded = bincode::serialize(&receipt).map_err(|e| e.to_string())?;
    let mut out = Vec::with_capacity(encoded.len() + 74);
    out.extend_from_slice(&(encoded.len() as u32).to_le_bytes());
    out.extend_from_slice(&encoded);
    out.extend_from_slice(&claim.csca_sha256);
    out.extend_from_slice(&claim.today.year.to_le_bytes());
    out.extend_from_slice(&[claim.today.month, claim.today.day, claim.min_age]);
    out.extend_from_slice(&claim.device_key_sha256);
    out.push(claim.chip_authenticated as u8);
    Ok(out)
}
