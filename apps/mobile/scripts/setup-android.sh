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

# 2. Merge res/values/strings.xml additions. The cap add scaffold
#    already has strings like title_activity_main that the
#    generated AndroidManifest.xml references; overwriting
#    strings.xml with the runner's would lose those, and
#    processReleaseResources then fails with
#      'resource string/title_activity_main not found'.
#    Append any <string name="..."> from the runner that isn't
#    already in the destination.
DEST_STRINGS="$ANDROID/app/src/main/res/values/strings.xml"
mkdir -p "$ANDROID/app/src/main/res/values"
if [[ -f "$DEST_STRINGS" && -f "$RUNNER/res/values/strings.xml" ]]; then
  while IFS= read -r line; do
    name=$(echo "$line" | grep -oE 'name="[^"]+"' | head -1 | sed 's/name="//;s/"$//')
    if [[ -n "$name" ]] && ! grep -q "name=\"$name\"" "$DEST_STRINGS"; then
      # Insert before the closing </resources> tag.
      sed -i "s|</resources>|    $line\n</resources>|" "$DEST_STRINGS"
    fi
  done < <(grep '<string ' "$RUNNER/res/values/strings.xml")
fi

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

# 4. Register HermesAPIPlugin in MainActivity. The cap scaffold's
#    default MainActivity is just `public class MainActivity extends
#    BridgeActivity {}` — no onCreate override. The original script
#    tried to sed-insert `registerPlugin(HermesAPIPlugin.class);`
#    after `super.onCreate(savedInstanceState);`, but that line
#    doesn't exist in the empty scaffold, so nothing was inserted
#    and the plugin was never registered at runtime. We now:
#      (a) check whether the registration call is already present,
#          using the call itself as the idempotency key (the class
#          name alone collides with the import on the next step);
#      (b) if there's an existing onCreate, insert the call after
#          super.onCreate;
#      (c) if there's no onCreate (the cap default), replace the
#          class body with a proper onCreate that calls
#          registerPlugin BEFORE super.onCreate.
MAIN_ACTIVITY=$(find "$ANDROID/app/src/main/java" -name "MainActivity.java" 2>/dev/null | head -1)
if [[ -n "$MAIN_ACTIVITY" ]]; then
  if ! grep -q "registerPlugin(HermesAPIPlugin.class)" "$MAIN_ACTIVITY"; then
    if grep -q "super.onCreate(savedInstanceState);" "$MAIN_ACTIVITY"; then
      sed -i '/super.onCreate(savedInstanceState);/a\        registerPlugin(HermesAPIPlugin.class);' "$MAIN_ACTIVITY"
    else
      # Replace the empty class body with a complete onCreate. The
      # class declaration line is preserved; the `{}` body is
      # expanded to include the override.
      sed -i 's|public class MainActivity extends BridgeActivity {}|public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(android.os.Bundle savedInstanceState) {\n        // Register custom plugins BEFORE super.onCreate so the\n        // bridge picks them up during initialization.\n        registerPlugin(HermesAPIPlugin.class);\n        super.onCreate(savedInstanceState);\n    }\n}|' "$MAIN_ACTIVITY"
    fi
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
