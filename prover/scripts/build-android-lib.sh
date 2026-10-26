#!/bin/sh
# Builds the prover as a JNI library for arm64 Android and copies it, with the NDK's
# libc++_shared.so it links against, into the app's jniLibs (git-ignored).
#
#     prover/scripts/build-android-lib.sh
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
ndk=/opt/homebrew/share/android-ndk/toolchains/llvm/prebuilt/darwin-x86_64
dest="$here/../android/app/src/main/jniLibs/arm64-v8a"

export CC_aarch64_linux_android=$ndk/bin/aarch64-linux-android29-clang
export CXX_aarch64_linux_android=$ndk/bin/aarch64-linux-android29-clang++
export AR_aarch64_linux_android=$ndk/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$ndk/bin/aarch64-linux-android29-clang
# No debug info in the shipped library: it would add over 100 MB to the APK.
(cd "$here" && cargo build --release -p age-prover-ffi --target aarch64-linux-android \
    --config profile.release.debug=0 --config profile.release.strip=true)

mkdir -p "$dest"
cp "$here/target/aarch64-linux-android/release/libage_prover.so" "$dest/"
cp "$ndk/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$dest/"
ls -la "$dest"
