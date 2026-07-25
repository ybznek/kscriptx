#!/usr/bin/env bash
# Build a UNIVERSAL SDKMAN zip (JVM-only), matching classic kscript layout:
#   kscriptx-<ver>/bin/{kscriptx,kscriptx.bat,kscriptx.jar,lib/,...}
#
# No native kotlinc and no Linux-only kscriptx-dclient — works on any OS with JDK 17+.
# Native kotlinc can still be installed under ~/.kscriptx/native-kotlinc (see README).
#
# Usage: ./scripts/package-sdkman.sh [version]
# Output: dist/kscriptx-<ver>-bin.zip
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
VERSION="${VERSION#v}"

# Fresh JVM-only tree (do not reuse a native-bundled dist/).
INCLUDE_NATIVE=0 "$ROOT/scripts/package-dist.sh" "$VERSION"

SRC="$ROOT/dist/kscriptx-${VERSION}"
STAGE_PARENT="$ROOT/dist"
STAGE_NAME="kscriptx-${VERSION}"
STAGE="$STAGE_PARENT/$STAGE_NAME"

# Ensure stage name matches SDKMAN convention (package-dist already uses this).
[[ -d "$SRC" ]] || { echo "missing $SRC" >&2; exit 1; }

# Drop Linux-only native client so the zip is truly UNIVERSAL.
rm -f "$STAGE/bin/kscriptx-dclient"
rm -rf "$STAGE/bin/native-kotlinc"

# Sanity: launchers + jar present
test -f "$STAGE/bin/kscriptx.jar"
test -f "$STAGE/bin/kscriptx"
test -f "$STAGE/bin/kscriptx.bat"

OUT="$ROOT/dist/kscriptx-${VERSION}-bin.zip"
rm -f "$OUT"

if command -v zip >/dev/null 2>&1; then
  (cd "$STAGE_PARENT" && zip -rq "$OUT" "$STAGE_NAME")
else
  python3 - "$STAGE_PARENT" "$STAGE_NAME" "$OUT" <<'PY'
import os, sys, zipfile
base, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
root = os.path.join(base, name)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for dirpath, _, files in os.walk(root):
        for f in files:
            path = os.path.join(dirpath, f)
            zf.write(path, os.path.relpath(path, base))
PY
fi

echo "Wrote $OUT ($(du -h "$OUT" | awk '{print $1}'))"
unzip -l "$OUT" | head -25
