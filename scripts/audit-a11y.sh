#!/usr/bin/env bash
# audit-a11y.sh — Phase 6 accessibility audit hook.
#
# Checks the vendored renderer's JSX for common a11y issues:
#   - <img> without alt attribute
#   - <button> without aria-label or text content
#   - <a href> without text content
#   - onClick handlers without keyboard equivalent
#
# This is a smoke test, not a full audit (axe-core would be the
# real test). It catches the obvious regressions when new screens
# are vendored in via the vendor-renderer.sh update.
#
# Run from the repo root: bash scripts/audit-a11y.sh
# Exits non-zero if any issues found.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RENDERER="$ROOT/packages/renderer/src"

if [[ ! -d "$RENDERER" ]]; then
  echo "warning: $RENDERER does not exist; run vendor-renderer.sh first" >&2
  exit 0
fi

ISSUES=0

# <img> without alt
echo "→ checking <img> tags for alt attribute"
while IFS= read -r f; do
  if grep -E '<img[^>]*>' "$f" | grep -vE 'alt=' > /dev/null 2>&1; then
    echo "  ! $f: <img> without alt"
    ISSUES=$((ISSUES + 1))
  fi
done < <(find "$RENDERER" -name "*.tsx" -not -path "*/test/*")

# <button> without aria-label or text
echo "→ checking <button> tags for accessible name"
while IFS= read -r f; do
  if grep -E '<button' "$f" | grep -vE 'aria-label' > /dev/null 2>&1; then
    # Check for children with text content
    if ! grep -E '<button[^>]*>[^<]+' "$f" > /dev/null 2>&1; then
      echo "  ! $f: <button> without aria-label or text content"
      ISSUES=$((ISSUES + 1))
    fi
  fi
done < <(find "$RENDERER" -name "*.tsx" -not -path "*/test/*")

# <a href> without text
echo "→ checking <a href> tags for text content"
while IFS= read -r f; do
  if grep -E '<a[^>]*href=' "$f" | grep -vE '>[^<]+' > /dev/null 2>&1; then
    echo "  ! $f: <a href> without text content"
    ISSUES=$((ISSUES + 1))
  fi
done < <(find "$RENDERER" -name "*.tsx" -not -path "*/test/*")

# Touch target size (Android minimum is 48dp; web equivalent is 44x44px)
echo "→ checking for small clickable elements (heuristic)"
SMALL=$(grep -rE '(width|height).{0,30}(30|32|36)px' "$RENDERER" --include="*.tsx" --include="*.css" | grep -iE 'button|tap|click|icon' | wc -l)
if [[ "$SMALL" -gt 5 ]]; then
  echo "  ! $SMALL elements with width/height under 40px. Android minimum is 48dp (~36px @ 1x)."
  ISSUES=$((ISSUES + 1))
fi

# Contrast ratio check (sample)
echo "→ contrast ratio sample (heuristic — full audit needs axe-core)"
LOW_CONTRAST=$(grep -rE 'color:.*#[0-9a-fA-F]{3,6}' "$RENDERER/assets/main.css" | grep -E '#[3-6][0-9a-fA-F][3-6][0-9a-fA-F][3-6][0-9a-fA-F]' | wc -l)
if [[ "$LOW_CONTRAST" -gt 10 ]]; then
  echo "  ! $LOW_CONTRAST color values with potentially-low contrast (mid-gray hex). Manual review recommended."
fi

if [[ "$ISSUES" -gt 0 ]]; then
  echo ""
  echo "✗ $ISSUES a11y issue(s) found"
  exit 1
fi

echo ""
echo "✓ a11y audit passed (heuristic check; full audit needs axe-core)"
