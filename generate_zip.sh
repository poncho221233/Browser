#!/usr/bin/env bash
# Rebuilds the complete project ZIP from the current tree
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-/home/workdir/artifacts/AntiDetectBrowser.zip}"
cd "$ROOT/.."
zip -r "$OUT" AntiDetectBrowser \
  -x "AntiDetectBrowser/.git/*" \
  -x "AntiDetectBrowser/**/build/*" \
  -x "AntiDetectBrowser/**/.gradle/*" \
  -x "AntiDetectBrowser/local.properties" \
  -x "AntiDetectBrowser/**/*.iml" \
  -x "AntiDetectBrowser/.idea/*"
echo "Created: $OUT"
ls -lh "$OUT"
