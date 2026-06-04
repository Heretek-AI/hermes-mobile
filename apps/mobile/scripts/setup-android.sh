#!/usr/bin/env bash
# setup-android.sh — copy the HermesAPIPlugin Kotlin sources from
# android-runner/ into the generated apps/mobile/android/ project, and
# register the plugin in MainActivity. Run this after `npx cap add
# android` (or as part of `pnpm run setup`).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
RUNNER="$ROOT/android-runner/app/src/main"
ANDROID="$ROOT/apps/mobile/android"
PKG_DIR="$ANDROID/app/src/main/kotlin/com/nousresearch/hermes"

if [[ ! -d "$ANDROID" ]]; then
  echo "error: $ANDROID does not exist. Run \`npx cap add android\` first." >&2
  exit 1
fi

# 1. Copy the HermesAPIPlugin.kt source.
mkdir -p "$PKG_DIR"
for f in "$RUNNER/kotlin/com/nousresearch/hermes/"*.kt; do
  cp -f "$f" "$PKG_DIR/"
done

# 2. Copy res/values/strings.xml additions.
mkdir -p "$ANDROID/app/src/main/res/values"
cp -f "$RUNNER/res/values/strings.xml" "$ANDROID/app/src/main/res/values/strings.xml"

# 3. Merge permissions from android-runner/AndroidManifest.xml into the
#    generated AndroidManifest.xml. We append <uses-permission> tags
#    that aren't already present.
MANIFEST="$ANDROID/app/src/main/AndroidManifest.xml"
RUNNER_MANIFEST="$RUNNER/AndroidManifest.xml"
if [[ -f "$MANIFEST" && -f "$RUNNER_MANIFEST" ]]; then
  # Match <uses-permission ... android:name="..."/> lines. The trailing
  # 'android:name=' anchor is critical: the runner manifest's header
  # comment mentions '<uses-permission>' by name, and a naive
  # '<uses-permission' grep would pick up that comment line too. The
  # inner grep -oE would then return no match, exit 1, and with
  # 'set -o pipefail' the whole assignment fails under 'set -e' —
  # the script was bailing on the FIRST line of this loop.
  while IFS= read -r line; do
    perm=$(echo "$line" | grep -oE 'android:name="[^"]+"' | head -1 | sed 's/android:name="//;s/"$//' || true)
    if [[ -n "$perm" ]] && ! grep -q "$perm" "$MANIFEST"; then
      # Insert before the closing </manifest> tag.
      sed -i "s|</manifest>|    $line\n</manifest>|" "$MANIFEST"
    fi
  done < <(grep -E '<uses-permission[^/]*android:name=' "$RUNNER_MANIFEST")
fi

# 4. Register HermesAPIPlugin in MainActivity. The generated file
#    extends BridgeActivity; we add a registerPlugin call.
MAIN_ACTIVITY=$(find "$ANDROID/app/src/main/java" -name "MainActivity.java" 2>/dev/null | head -1)
if [[ -n "$MAIN_ACTIVITY" ]]; then
  if ! grep -q "HermesAPIPlugin" "$MAIN_ACTIVITY"; then
    # Find the onCreate method and insert registerPlugin(PluginCall) calls
    # after super.onCreate().
    sed -i '/super.onCreate(savedInstanceState);/a\        registerPlugin(HermesAPIPlugin.class);' "$MAIN_ACTIVITY"
  fi
fi

# 5. Add the plugin import.
if [[ -n "$MAIN_ACTIVITY" ]]; then
  if ! grep -q "import com.nousresearch.hermes.HermesAPIPlugin" "$MAIN_ACTIVITY"; then
    sed -i '/^import com.getcapacitor.BridgeActivity;/a import com.nousresearch.hermes.HermesAPIPlugin;' "$MAIN_ACTIVITY"
  fi
fi

echo "android setup complete:"
echo "  - $PKG_DIR/HermesAPIPlugin.kt"
echo "  - $ANDROID/app/src/main/res/values/strings.xml"
echo "  - $MANIFEST (permissions merged)"
echo "  - $MAIN_ACTIVITY (plugin registered)"
echo ""
echo "next: \`pnpm run cap:sync\` to refresh Capacitor's plugin registry."
