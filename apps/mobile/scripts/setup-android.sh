#!/usr/bin/env bash
# setup-android.sh — sync the canonical Android sources from
# android-runner/ into apps/mobile/android/, and merge resource and
# manifest additions.
#
# The runner is the single source of truth for all Hermes Kotlin
# sources (per groovy-fluttering-island.md Phase 0c). After running
# this script, the live apps/mobile/android/ tree mirrors the runner
# exactly. CI enforces this with script/check-kotlin-drift.sh.
#
# Idempotent: safe to re-run after every build. Uses rsync --delete
# so files removed from the runner also disappear from the live tree.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
RUNNER="$ROOT/android-runner/app/src/main"
ANDROID="$ROOT/apps/mobile/android"
LIVE_KOTLIN="$ANDROID/app/src/main/kotlin/com/nousresearch/hermes"
RUNNER_KOTLIN="$RUNNER/kotlin/com/nousresearch/hermes"

if [[ ! -d "$ANDROID" ]]; then
  echo "error: $ANDROID does not exist. Run \`pnpm run setup\` first." >&2
  exit 1
fi

if [[ ! -d "$RUNNER_KOTLIN" ]]; then
  echo "error: $RUNNER_KOTLIN does not exist. android-runner tree is broken." >&2
  exit 1
fi

# 1. rsync the entire Kotlin tree (--delete keeps them in sync).
mkdir -p "$LIVE_KOTLIN"
if ! rsync -a --delete \
    --exclude='.DS_Store' \
    "$RUNNER_KOTLIN/" "$LIVE_KOTLIN/"; then
  echo "error: rsync of Kotlin sources failed." >&2
  exit 1
fi

# 2. Merge strings.xml additions. The Android scaffold has its own
#    strings (e.g. title_activity_main) referenced by the generated
#    AndroidManifest.xml; overwriting the file with the runner's would
#    drop them and break processReleaseResources. We append any
#    <string name="..."> from the runner that isn't already present.
DEST_STRINGS="$ANDROID/app/src/main/res/values/strings.xml"
if [[ -f "$DEST_STRINGS" && -f "$RUNNER/res/values/strings.xml" ]]; then
  while IFS= read -r line; do
    name=$(echo "$line" | grep -oE 'name="[^"]+"' | head -1 | sed 's/name="//;s/"$//')
    if [[ -n "$name" ]] && ! grep -q "name=\"$name\"" "$DEST_STRINGS"; then
      sed -i "s|</resources>|    $line\n</resources>|" "$DEST_STRINGS"
    fi
  done < <(grep '<string ' "$RUNNER/res/values/strings.xml")
fi

# 3. Merge <uses-permission> tags from android-runner/AndroidManifest.xml
#    into the generated apps/mobile/android/app/src/main/AndroidManifest.xml.
#    The runner's manifest is the canonical list of permissions the
#    Hermes backend needs (gateway FGS, OAuth, deep links, file IO).
MANIFEST="$ANDROID/app/src/main/AndroidManifest.xml"
RUNNER_MANIFEST="$RUNNER/AndroidManifest.xml"
if [[ -f "$MANIFEST" && -f "$RUNNER_MANIFEST" ]]; then
  while IFS= read -r line; do
    perm=$(echo "$line" | grep -oE 'android:name="[^"]+"' | head -1 | sed 's/android:name="//;s/"$//' || true)
    if [[ -n "$perm" ]] && ! grep -q "$perm" "$MANIFEST"; then
      sed -i "s|</manifest>|    $line\n</manifest>|" "$MANIFEST"
    fi
  done < <(grep -E '<uses-permission[^/]*android:name=' "$RUNNER_MANIFEST")
fi

# 4. (Removed in Phase 0) HermesAPIPlugin registration and the
#    associated MainActivity.java BridgeActivity patch. The WebView
#    path is gone; MainActivity is now Kotlin+Compose and lives under
#    apps/mobile/android/app/src/main/kotlin/com/nousresearch/hermes/.

echo "android setup complete:"
echo "  - $(find "$LIVE_KOTLIN" -name '*.kt' | wc -l) Kotlin sources synced from runner"
echo "  - $ANDROID/app/src/main/res/values/strings.xml (merged)"
echo "  - $MANIFEST (permissions merged)"
echo ""
echo "next: \`./gradlew :app:assembleDebug\` to build."
