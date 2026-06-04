#!/usr/bin/env bash
# f-droid/build.sh — reproducible build script for the F-Droid
# build server.
#
# The F-Droid server runs this in a clean container with a pinned
# toolchain (NDK r26b, gradle 8.7, OpenJDK 17). The script must
# produce a byte-identical APK across runs to satisfy F-Droid's
# "Reproducible Builds" requirement.
#
# Reproducibility notes:
# - We build the universal APK (not per-ABI) so the apksigner
#   output is identical regardless of host architecture.
# - The hermes-agent SHA is pinned in
#   apps/mobile/scripts/hermes-agent-version.txt — F-Droid
#   verifies this matches the metadata file's srclibs entry.
# - We do NOT bundle Sentry (the Sentry SDK has a closed-source
#   component that violates F-Droid's allowed-nonfree list).
#   HermesAPI.setCrashReportingEnabled is a no-op in F-Droid
#   builds.
# - We do NOT enable the in-app self-updater. F-Droid's
#   "Update" button is the canonical update path.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 1. Install workspace dependencies.
echo "→ pnpm install"
pnpm install --frozen-lockfile

# 2. Vendor the desktop renderer.
echo "→ vendor renderer"
bash apps/mobile/scripts/vendor-renderer.sh

# 3. Build the Vite bundle.
echo "→ vite build"
pnpm run build

# 4. Run cap sync to copy web assets to the android project.
echo "→ cap sync android"
pnpm --filter @hermes-mobile/app cap sync android

# 5. Set up the F-Droid keystore (none — F-Droid signs with
#    their own key). We create an empty keystore.properties so
#    the Gradle signingConfig reads it but doesn't actually sign.
echo "→ empty keystore.properties (F-Droid signs with their own key)"
mkdir -p keystore
cat > keystore/keystore.properties <<'EOF'
storeFile=
storePassword=
keyAlias=
keyPassword=
EOF

# 6. Copy the build.gradle template (Phase 6: ABI splits + signing).
echo "→ install build.gradle template"
cp android-runner/app/build.gradle.template apps/mobile/android/app/build.gradle

# 7. Run gradle assembleRelease. The F-Droid build server runs
#    this with their own signing key via `apksigner` after
#    `assembleRelease` produces the unsigned APK. The
#    `universalApk true` in build.gradle produces the fat APK
#    that F-Droid packages.
echo "→ gradle assembleRelease"
cd apps/mobile/android
./gradlew assembleRelease --no-daemon \
  --no-build-cache \
  --scan \
  -Pandroid.injected.testOnly=false

# 8. Verify the output APK exists.
APK="$ROOT/apps/mobile/android/app/build/outputs/apk/release/app-universal-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "error: expected $APK but it doesn't exist" >&2
  exit 1
fi

# 9. Copy the unsigned APK to f-droid/output/ for the workflow
#    to upload as an artifact. F-Droid's CI re-signs it.
mkdir -p "$ROOT/f-droid/output"
cp "$APK" "$ROOT/f-droid/output/"
echo "✓ built $(basename "$APK")"
ls -la "$ROOT/f-droid/output/"
