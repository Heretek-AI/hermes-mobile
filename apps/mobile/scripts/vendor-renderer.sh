#!/usr/bin/env bash
# vendor-renderer.sh
#
# Sync the desktop's React renderer from review/hermes-desktop/ into
# packages/renderer/src/ so the mobile app can mount it. Apply the small set
# of mobile-specific patches documented in the plan (analytics, voice, file
# picker, share, mobile shell, back button).
#
# Idempotent: safe to re-run after pulling upstream changes.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# Source paths default to the local `review/hermes-desktop/` checkout
# (used by maintainers doing local vendor work). The CI workflow clones
# the desktop repo at a pinned SHA and sets HERMES_DESKTOP_SRC +
# HERMES_DESKTOP_SHARED_SRC to point at that clone — that path is
# preferred when set.
SRC="${HERMES_DESKTOP_SRC:-$ROOT/review/hermes-desktop/src/renderer/src}"
SHARED_SRC="${HERMES_DESKTOP_SHARED_SRC:-$ROOT/review/hermes-desktop/src/shared}"
DST="$ROOT/packages/renderer/src"
# Place vendored shared/ one level above the renderer so the desktop's
# `../../../../shared/foo` relative paths resolve. The desktop's
# structure is `src/renderer/src/screens/.../Foo.tsx` →
# `../../../../shared/foo` = `<repo>/src/shared/foo`. We mirror that
# here as `packages/renderer/src` ↔ `packages/renderer/../shared` (one
# level up, since the renderer's depth is 1 shallower than the desktop's).
SHARED_DST="$ROOT/packages/shared"
SHIMS_SRC="$ROOT/apps/mobile/scripts/renderer-shims"

if [[ ! -d "$SRC" ]]; then
  echo "error: $SRC not found." >&2
  echo "  Local devs: check out fathah/hermes-desktop at the SHA in" >&2
  echo "    apps/mobile/scripts/hermes-desktop-version.txt into" >&2
  echo "    review/hermes-desktop/." >&2
  echo "  CI: set HERMES_DESKTOP_SRC (and HERMES_DESKTOP_SHARED_SRC) to" >&2
  echo "    a clone of fathah/hermes-desktop." >&2
  exit 1
fi

# Preserve mobile-only files (shims + global.d.ts) across rsync --delete.
# The desktop's tree never contains these names so rsync would happily
# remove them. We snapshot them, run rsync, then restore.
PRESERVE_DIR="$(mktemp -d)"
trap "rm -rf $PRESERVE_DIR" EXIT
[[ -d "$DST/shims" ]] && cp -a "$DST/shims" "$PRESERVE_DIR/shims"
[[ -f "$DST/global.d.ts" ]] && cp -a "$DST/global.d.ts" "$PRESERVE_DIR/global.d.ts"
[[ -f "$DST/index.ts" ]] && cp -a "$DST/index.ts" "$PRESERVE_DIR/index.ts"

# Mirror the renderer tree, dropping files we don't want (Electron-only or dev-only).
rsync -a --delete \
  --exclude='.DS_Store' \
  --exclude='__snapshots__' \
  "$SRC/" "$DST/"

# Drop Electron-specific files. The renderer should not import main-process
# types or Node built-ins.
rm -f "$DST/env.d.ts"

# Mirror the desktop's `shared/` directory so the renderer's
# `../../../../shared/foo` relative imports resolve. The path goes 4
# levels up from `packages/renderer/src/screens/.../Foo.tsx`:
#   .. = packages/renderer/src/screens/
#   .. = packages/renderer/src/
#   .. = packages/renderer/
#   .. = packages/
# So we put the vendored shared/ at `packages/shared/`.
if [[ -d "$SHARED_SRC" ]]; then
  mkdir -p "$SHARED_DST"
  rsync -a --delete \
    --exclude='.DS_Store' \
    --exclude='*.test.ts' \
    "$SHARED_SRC/" "$SHARED_DST/"
fi

# Restore + refresh mobile-only shims.
if [[ -d "$PRESERVE_DIR/shims" ]]; then
  cp -a "$PRESERVE_DIR/shims" "$DST/shims"
fi
[[ -f "$PRESERVE_DIR/global.d.ts" ]] && cp -a "$PRESERVE_DIR/global.d.ts" "$DST/global.d.ts"
[[ -f "$PRESERVE_DIR/index.ts" ]] && cp -a "$PRESERVE_DIR/index.ts" "$DST/index.ts"
if [[ -d "$SHIMS_SRC" ]]; then
  mkdir -p "$DST/shims"
  cp -a "$SHIMS_SRC/global.d.ts" "$DST/global.d.ts"
  cp -a "$SHIMS_SRC/assets.d.ts" "$DST/shims/assets.d.ts"
  cp -a "$SHIMS_SRC/posthog-js.ts" "$DST/shims/posthog-js.ts"
  cp -a "$SHIMS_SRC/test-globals.d.ts" "$DST/shims/test-globals.d.ts"
fi

# Apply mobile-specific patches. Two flavours:
#   1. `*.patch` — unified diff, applied with `git apply` (idempotent).
#   2. `*.tsx` / `*.ts` — wholesale file replacement; each file here
#      overwrites the corresponding path in the vendored tree.
#      Used for files where the diff is large enough that a patch is
#      noisier than a full file (e.g. AgentMarkdown.tsx in Phase 4
#      swapping react-syntax-highlighter → highlight.js).
PATCHES_DIR="$ROOT/apps/mobile/scripts/renderer-patches"
if [[ -d "$PATCHES_DIR" ]]; then
  # (1) Unified-diff patches
  for patch in "$PATCHES_DIR"/*.patch; do
    [[ -f "$patch" ]] || continue
    if git apply --check "$patch" >/dev/null 2>&1; then
      git apply "$patch"
      echo "applied $(basename "$patch")"
    else
      echo "skipping $(basename "$patch") (already applied or drift)"
    fi
  done
  # (2) Wholesale file replacements (recursive — patch path mirrors
  # the destination path, so e.g. components/AgentMarkdown.tsx
  # overwrites packages/renderer/src/components/AgentMarkdown.tsx).
  if [[ -d "$PATCHES_DIR" ]]; then
    while IFS= read -r -d '' f; do
      rel="${f#$PATCHES_DIR/}"
      # rm -f first because some filesystems (notably overlayfs
      # and btrfs with copy-on-write) silently no-op on cp -f when
      # the source and dest are on the same backing store.
      rm -f "$DST/$rel"
      cp -f "$f" "$DST/$rel"
      echo "applied renderer file: $rel"
    done < <(find "$PATCHES_DIR" -type f \( -name '*.tsx' -o -name '*.ts' \) -print0)
  fi
fi

echo "vendored renderer: $DST ($(find "$DST" -name '*.tsx' -o -name '*.ts' | wc -l) files)"
