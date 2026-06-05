#!/usr/bin/env bash
# f-droid/build.sh — reproducible build script for the F-Droid
# build server.
#
# The F-Droid server runs this in a clean container with a pinned
# toolchain (NDK r27d LTS, gradle 8.7, OpenJDK 21). The script must
# produce a byte-identical APK across runs to satisfy F-Droid's
# "Reproducible Builds" requirement.
#
# Reproducibility notes:
# - We build the universal APK (not per-ABI) so the apksigner
#   output is identical regardless of host architecture.
# - We do NOT bundle Sentry (the Sentry SDK has a closed-source
#   component that violates F-Droid's allowed-nonfree list).
#   HermesApi.setCrashReportingEnabled is a no-op in F-Droid
#   builds; the SentryAndroid.init() call in
#   HermesApp.maybeInitSentry() never runs because the DSN is
#   empty in the open-source build.
# - We do NOT enable the in-app self-updater. F-Droid's
#   "Update" button is the canonical update path.
# - We produce an UNSIGNED APK; F-Droid re-signs with their
#   own key externally. The v0.1.0 release-pipeline fail-fast
#   is bypassed with `-Phermes.fdroid=true`.
#
# v0.1.0: Phase 0 deleted the WebView+Capacitor+vendored-renderer
# scaffolding. The pre-Phase-0 vendor-renderer.sh, vite build,
# and cap sync steps are removed. The Android project is now
# pure Compose (no Vite, no cap, no JS bundle).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 1. Install workspace dependencies. The post-Phase-0
#    apps/mobile/package.json is a minimal stub with no JS deps;
#    pnpm install is a no-op (the lockfile is empty) but we run
#    it for consistency with the dev workflow.
echo "→ pnpm install"
pnpm install --frozen-lockfile

# 2. Set up the F-Droid keystore (none — F-Droid signs with
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

# 3. Copy the build.gradle.template (Phase 6: ABI splits +
#    signing + Sentry dep). v0.1.0: the template and live
#    build.gradle are kept in sync by the CI's sync step.
echo "→ install build.gradle template"
cp android-runner/app/build.gradle.template apps/mobile/android/app/build.gradle

# 4. Run gradle assembleRelease. The F-Droid build server
#    re-signs with their own key via `apksigner` after
#    assembleRelease produces the unsigned APK. The
#    universalApk true in build.gradle produces the fat APK
#    that F-Droid packages. `-Phermes.fdroid=true` bypasses
#    the v0.1.0 release-pipeline signing fail-fast (which
#    would otherwise reject the empty keystore.properties).
#
#    v0.1.0: pass `clean assembleRelease` so a fresh build
#    runs. The previous `assembleRelease` (for the GitHub
#    Release) leaves signed APKs in build/outputs/apk/release/.
#    The F-Droid build re-evaluates the signing config (which
#    is null for F-Droid) and the resulting packageRelease
#    task overwrites the per-ABI APKs but the universal APK
#    packaging task is UP-TO-DATE-skipped, so the universal
#    APK file disappears. A `clean` forces a from-scratch
#    build that produces all 4 APKs (per-ABI + universal).
echo "→ gradle clean assembleRelease"
cd apps/mobile/android
./gradlew clean assembleRelease --no-daemon \
  --no-build-cache \
  --scan \
  -Pandroid.injected.testOnly=false \
  -Phermes.fdroid=true

# 5. Verify the output APK exists.
APK="$ROOT/apps/mobile/android/app/build/outputs/apk/release/app-universal-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "error: expected $APK but it doesn't exist" >&2
  exit 1
fi

# 6. Copy the unsigned APK to f-droid/output/ for the workflow
#    to upload as an artifact. F-Droid's CI re-signs it.
mkdir -p "$ROOT/f-droid/output"
cp "$APK" "$ROOT/f-droid/output/"
echo "✓ built $(basename "$APK")"
ls -la "$ROOT/f-droid/output/"
