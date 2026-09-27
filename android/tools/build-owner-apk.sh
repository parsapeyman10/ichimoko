#!/usr/bin/env bash
# For the repository owner only: build an APK with THEIR stable private signing key.
# This is not a Play Protect bypass and cannot replace Play distribution/review.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 /absolute/path/outside/repo/aurum-edge.jks key-alias" >&2
  exit 2
fi
repo="$(cd "$(dirname "$0")/../.." && pwd -P)"
if [[ ! -f "$1" ]]; then
  echo "Keystore file not found; create and back it up outside the repository." >&2
  exit 2
fi
store="$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")"
case "$store" in "$repo"/*)
  echo "Move the keystore outside the repository; never commit/private-share it." >&2
  exit 2;;
esac

export AURUM_RELEASE_STORE_FILE="$store"
export AURUM_RELEASE_KEY_ALIAS="$2"
if [[ -z "${AURUM_RELEASE_STORE_PASSWORD:-}" ]]; then
  [[ -t 0 ]] || { echo "Supply signing passwords securely or run interactively." >&2; exit 2; }
  read -r -s -p "Keystore password: " AURUM_RELEASE_STORE_PASSWORD; echo
  export AURUM_RELEASE_STORE_PASSWORD
fi
if [[ -z "${AURUM_RELEASE_KEY_PASSWORD:-}" ]]; then
  [[ -t 0 ]] || { echo "Supply key password securely or run interactively." >&2; exit 2; }
  read -r -s -p "Key password: " AURUM_RELEASE_KEY_PASSWORD; echo
  export AURUM_RELEASE_KEY_PASSWORD
fi
trap 'unset AURUM_RELEASE_STORE_PASSWORD AURUM_RELEASE_KEY_PASSWORD' EXIT
[[ -n "$AURUM_RELEASE_STORE_PASSWORD" && -n "$AURUM_RELEASE_KEY_PASSWORD" ]] || {
  echo "Empty signing password is not allowed." >&2; exit 2;
}

cd "$repo/android"
./gradlew --no-daemon -PaurumRequireReleaseSigning=true testDebugUnitTest assembleRelease
apk="app/build/outputs/apk/release/app-release.apk"
[[ -f "$apk" ]] || { echo "Owner-signed APK was not produced." >&2; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$sdk" && -x "$sdk/build-tools/35.0.0/apksigner" ]]; then
  signer="$sdk/build-tools/35.0.0/apksigner"
elif command -v apksigner >/dev/null 2>&1; then
  signer="$(command -v apksigner)"
else
  echo "APK built, but apksigner is required to verify its certificate before sharing." >&2
  exit 1
fi
"$signer" verify --verbose --print-certs "$apk"
echo "APK: $PWD/$apk"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$apk"
elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$apk"; fi
echo "Keep the keystore AND its passwords private and backed up; sign every update with the SAME key."
