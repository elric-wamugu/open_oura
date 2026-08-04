#!/bin/bash
# Build the shared Rust core (oura-core) for Android and generate the Kotlin UniFFI
# bindings the app compiles against. The Android counterpart to
# apps/ios/build-xcframework.sh. Re-run after changing any Rust core code.
#
# Outputs:
#   apps/android/app/src/main/jniLibs/arm64-v8a/liboura_core.so   (loaded at runtime)
#   apps/android/app/src/main/java/uniffi/oura_core/oura_core.kt  (generated bindings)
#
# Requires: cargo-ndk, the aarch64-linux-android rust target, and an installed NDK.
#   cargo install cargo-ndk && rustup target add aarch64-linux-android
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$PWD"

ABI="arm64-v8a"                 # Pixel 5 + the Apple-Silicon emulator are both arm64.
TARGET="aarch64-linux-android"
APP="$REPO/apps/android/app/src/main"
JNI="$APP/jniLibs"
KOTLIN="$APP/java"

# Locate the NDK: honour an explicit env var, else take the highest installed version.
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  ANDROID_NDK_HOME="$(find "$SDK/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  [[ -n "$ANDROID_NDK_HOME" ]] || { echo "no NDK found under $SDK/ndk — install one via Android Studio's SDK Manager"; exit 1; }
  export ANDROID_NDK_HOME
fi
echo "==> NDK: $ANDROID_NDK_HOME"

# IMPORTANT: the workspace release profile sets `strip = true`, which removes .symtab.
# UniFFI's library-mode bindgen reads its UNIFFI_META_* entries from .symtab (.dynsym
# alone is not enough), so a stripped .so makes `generate --library` exit 0 and emit
# NOTHING — a silent no-op that looks like success. Keep the symbols for this build; the
# .so is only ~2.9 MB either way and Gradle strips debug info for release packaging.
export CARGO_PROFILE_RELEASE_STRIP=false

echo "==> build oura-core (release) for $ABI"
mkdir -p "$JNI" "$KOTLIN"
cargo ndk -t "$ABI" -o "$JNI" build --release -p oura-core

echo "==> generate Kotlin bindings"
rm -rf "${KOTLIN:?}/uniffi"
cargo run --quiet --bin uniffi-bindgen -- generate \
  --library "$REPO/target/$TARGET/release/liboura_core.so" \
  --language kotlin --out-dir "$KOTLIN"

# Fail loudly rather than leaving the app to fail at link time on an empty binding dir.
KT="$KOTLIN/uniffi/oura_core/oura_core.kt"
[[ -s "$KT" ]] || { echo "bindgen produced no Kotlin — is the .so stripped?"; exit 1; }

echo "✓ $JNI/$ABI/liboura_core.so"
echo "✓ $KT ($(wc -l < "$KT" | tr -d ' ') lines)"
echo
echo "Gradle needs the JNA aar for these bindings to load:"
echo '    implementation("net.java.dev.jna:jna:5.14.0@aar")'
