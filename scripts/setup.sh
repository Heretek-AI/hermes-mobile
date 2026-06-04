#!/usr/bin/env bash
# setup.sh — top-level setup. Installs dependencies, vendors the
# renderer, and prepares the Android project on first run.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "→ installing workspace dependencies"
pnpm install

echo "→ vendoring desktop renderer from review/hermes-desktop/"
bash apps/mobile/scripts/vendor-renderer.sh

echo "→ checking for Capacitor android project"
if [[ ! -d "apps/mobile/android" ]]; then
  echo "  android/ not present, generating with \`cap add android\`"
  pnpm --filter @hermes-mobile/app exec cap add android
fi

echo "→ copying HermesAPIPlugin into android/"
bash apps/mobile/scripts/setup-android.sh

echo "→ syncing Capacitor"
pnpm run cap:sync

echo ""
echo "✓ setup complete"
echo ""
echo "next:"
echo "  pnpm dev:mobile               # vite dev server (browser)"
echo "  pnpm android:run              # install debug APK to attached device"
echo "  pnpm --filter @hermes-mobile/app cap run android   # full cap-run"
