#!/bin/sh
# Builds the verifier as a WebAssembly module (decision 0010) and copies it to
# target/wasm/age_verifier.wasm.
#
#     prover/scripts/build-wasm.sh
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
# No debug info: the release profile keeps line tables for the native prover.
(cd "$here" && cargo build --release -p age-verifier-wasm --target wasm32-unknown-unknown \
    --config profile.release.debug=0 --config profile.release.strip=true)

mkdir -p "$here/target/wasm"
cp "$here/target/wasm32-unknown-unknown/release/age_verifier_wasm.wasm" "$here/target/wasm/age_verifier.wasm"
ls -l "$here/target/wasm/age_verifier.wasm"
