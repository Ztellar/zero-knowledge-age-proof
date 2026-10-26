#!/bin/sh
# Stage 2: build the in-process prover for Android, push it with the mock fixtures and
# run one mode on the connected phone, reporting peak RSS (VmHWM) of the process.
#
#     prover/scripts/android-measure.sh [execute|composite|succinct]
#
# Needs: Homebrew `android-ndk`, `rustup target add aarch64-linux-android`, a phone with
# USB debugging, and fixtures/out/ from fixtures/gen.py.
set -eu
mode=${1:-succinct}
here=$(cd "$(dirname "$0")/.." && pwd)
ndk=/opt/homebrew/share/android-ndk/toolchains/llvm/prebuilt/darwin-x86_64
remote=/data/local/tmp/zkage

export CC_aarch64_linux_android=$ndk/bin/aarch64-linux-android29-clang
export CXX_aarch64_linux_android=$ndk/bin/aarch64-linux-android29-clang++
export AR_aarch64_linux_android=$ndk/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$ndk/bin/aarch64-linux-android29-clang
(cd "$here" && cargo build --release -p host --features prove --target aarch64-linux-android)

adb shell mkdir -p $remote/fixtures
adb push "$here/target/aarch64-linux-android/release/host" \
    "$ndk/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" $remote/ >/dev/null
adb push "$here"/fixtures/out/csca.der "$here"/fixtures/out/sod.der \
    "$here"/fixtures/out/dg1.bin $remote/fixtures/ >/dev/null

# Polls VmHWM every 0.5 s while the prover runs; the kernel keeps the peak, so the last
# reading is the process maximum.
# `./host` must be the backgrounded command itself: backgrounding a `&&` chain would make
# `$!` a subshell, and VmHWM would read that shell's few kB instead of the prover's.
adb shell "cd $remote && chmod +x host; \
    LD_LIBRARY_PATH=$remote FIXTURES_DIR=$remote/fixtures ./host $mode > out.txt 2>&1 & \
    pid=\$!; hwm=0; \
    while kill -0 \$pid 2>/dev/null; do \
      v=\$(grep VmHWM /proc/\$pid/status 2>/dev/null | awk '{print \$2}'); \
      [ -n \"\$v\" ] && hwm=\$v; sleep 0.5; \
    done; wait \$pid; echo exit: \$?; cat $remote/out.txt; echo VmHWM: \$hwm kB"
