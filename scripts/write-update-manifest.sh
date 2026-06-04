#!/usr/bin/env bash
# write-update-manifest.sh — emit latest.json after a successful
# release build. The HermesAPI.checkForUpdates() IPC method on
# Android reads this from
# https://releases.nousresearch.com/hermes-mobile/latest.json,
# compares the versionCode against BuildConfig.VERSION_CODE, and
# if newer, downloads the matching APK and triggers the system
# install intent.
#
# Called by .github/workflows/mobile-build.yml after the
# sign-and-zipalign step.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT/apps/mobile/android/app/build/outputs/apk/release"
VERSION_NAME="${GITHUB_REF_NAME#mobile-v}"  # strip 'mobile-v' prefix
RELEASE_URL_BASE="https://github.com/Heretek-AI/hermes-mobile/releases/download/${GITHUB_REF_NAME:-mobile-v0.0.0}"

# Find the versionCode by parsing the gradle build output. We
# could also read it from the build.gradle, but this is robust
# to whatever the maintainer set.
VERSION_CODE="${HERMES_VERSION_CODE:-1}"

OUT="$ROOT/latest.json"

# Compute SHA-256 for each ABI variant. The latest.json shape
# uses a single `url` per release (the universal APK), with the
# per-ABI URLs in `assets` for users who want the smaller
# architecture-specific build.
echo "{" > "$OUT"
echo "  \"versionName\": \"$VERSION_NAME\"," >> "$OUT"
echo "  \"versionCode\": $VERSION_CODE," >> "$OUT"
echo "  \"url\": \"$RELEASE_URL_BASE/hermes-mobile-$VERSION_NAME-universal.apk\"," >> "$OUT"
echo "  \"releaseNotes\": \"https://github.com/Heretek-AI/hermes-mobile/releases/tag/${GITHUB_REF_NAME:-mobile-v0.0.0}\"," >> "$OUT"
echo "  \"assets\": {" >> "$OUT"

FIRST=true
for apk in "$APK_DIR"/*.apk; do
  [[ -f "$apk" ]] || continue
  filename="$(basename "$apk")"
  # Skip the universal APK (it's the main `url` field).
  case "$filename" in
    *universal*) continue ;;
  esac
  sha="$(sha256sum "$apk" | awk '{print $1}')"
  size="$(stat -c%s "$apk" 2>/dev/null || stat -f%z "$apk")"
  abi=""
  case "$filename" in
    *arm64-v8a*) abi="arm64-v8a" ;;
    *armeabi-v7a*) abi="armeabi-v7a" ;;
    *x86_64*) abi="x86_64" ;;
  esac
  if [[ -z "$abi" ]]; then continue; fi
  if [[ "$FIRST" == "true" ]]; then FIRST=false; else echo "," >> "$OUT"; fi
  cat >> "$OUT" <<ENTRY
    "$abi": {
      "url": "$RELEASE_URL_BASE/$filename",
      "sha256": "$sha",
      "size": $size
    }
ENTRY
done
echo "  }" >> "$OUT"
echo "}" >> "$OUT"

echo "✓ wrote $OUT:"
cat "$OUT"
