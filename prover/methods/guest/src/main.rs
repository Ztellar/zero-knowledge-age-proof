//! Runs inside the zkVM. A receipt for this program means `age_core::verify` accepted the
//! private chip data; only the `Claim` reaches the journal.

use age_core::{verify_traced, InputsRef};
use std::io::Read;

use risc0_zkvm::guest::env;

fn main() {
    let start = env::cycle_count();
    let mut bytes = Vec::new();
    env::stdin().read_to_end(&mut bytes).unwrap();
    let inputs = InputsRef::from_bytes(&bytes).expect("malformed inputs");
    let mut last = env::cycle_count();
    env::log(&format!("cycles 0 startup: {start}, read inputs: {}", last - start));
    let mut step = |name: &'static str| {
        let now = env::cycle_count();
        env::log(&format!("cycles {name}: {}", now - last));
        last = now;
    };
    let claim = verify_traced(inputs, &mut step).expect("chip data does not satisfy the claim");
    env::commit(&claim);
}
