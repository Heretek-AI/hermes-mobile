#!/usr/bin/env bash
# sign-and-zipalign.sh — post-build step that signs the release APK
# with the release keystore, then runs zipalign so the APK is
# installable via adb install / Play Store / F-Droid.
#
# Called by .github/workflows/mobile-build.yml after `gradlew
# assembleRelease`. The keystore + password come from the
# environment (KEYSTORE_FILE base64, KEYSTORE_PASSWORD, KEY_PASSWORD)
# so the secrets never live on disk in CI.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT/apps/mobile/android/app/build/outputs/apk/release"
KEYSTORE_FILE="${KEYSTORE_FILE:-/tmp/release.jks}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-hermes}"
KEY_PASSWORD="${KEY_PASSWORD:-}"

if [[ ! -d "$APK_DIR" ]]; then
  echo "error: $APK_DIR does not exist. Run \`gradlew assembleRelease\` first." >&2
  exit 1
fi

# GitHub Actions: KEYSTORE_FILE is base64-encoded in the secret;
# decode to a temp file before apksigner can read it.
if [[ -n "${KEYSTORE_FILE_B64:-}" && ! -f "$KEYSTORE_FILE" ]]; then
  echo "$KEYSTORE_FILE_B64" | base64 -d > "$KEYSTORE_FILE"
fi

if [[ ! -f "$KEYSTORE_FILE" ]]; then
  echo "error: keystore not found at $KEYSTORE_FILE" >&2
  exit 1
fi

# Sign and zipalign each ABI variant. We sign then zipalign
# (the correct order — zipalign must run on the unsigned APK
# because the alignment is part of the v2 signature).
shopt -s nullglob
for unsigned in "$APK_DIR"/*-unsigned.apk "$APK_DIR"/app-*-release-unsigned.apk; do
  aligned="$(dirname "$unsigned")/$(basename "$unsigned" -unsigned.apk)-aligned.apk"
  signed="$(dirname "$unsigned")/$(basename "$unsigned" -unsigned.apk).apk"

  echo "→ zipalign: $unsigned"
  zipalign -p -f 4 "$unsigned" "$aligned"

  echo "→ apksigner: $aligned"
  apksigner sign \
    --ks "$KEYSTORE_FILE" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$signed" \
    "$aligned"

  # Verify the signature.
  apksigner verify --print-certs "$signed" | head -5

  # Remove the intermediate -aligned.apk and the unsigned.
  rm -f "$aligned" "$unsigned"
done

echo ""
echo "✓ signed APKs in $APK_DIR:"
ls -la "$APK_DIR"/*.apk 2>&1
