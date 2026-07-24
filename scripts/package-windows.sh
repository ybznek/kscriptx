#!/usr/bin/env bash
# Build a Windows portable zip (works from Linux or Windows Git Bash).
# Usage: ./scripts/package-windows.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  if compgen -G "$ROOT/dist/kscriptx-*/VERSION" >/dev/null; then
    VERSION="$(cat "$ROOT/dist"/kscriptx-*/VERSION | head -1)"
  else
    VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
  fi
fi
VERSION="${VERSION#v}"

DIST="$ROOT/dist/kscriptx-${VERSION}"
if [[ ! -d "$DIST" ]]; then
  "$ROOT/scripts/package-dist.sh" "$VERSION"
fi

STAGE="$ROOT/dist/kscriptx-${VERSION}-windows-amd64"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$DIST/bin" "$DIST/share" "$STAGE/" 2>/dev/null || cp -r "$DIST/bin" "$DIST/share" "$STAGE/"
printf '%s\n' "$VERSION" >"$STAGE/VERSION"
cat >"$STAGE/README-WINDOWS.txt" <<EOF
kscriptx ${VERSION} for Windows
================================

Requirements: JDK 17+ on PATH (or JAVA_HOME set).

Run:
  bin\\kscriptx.bat examples\\hello.kts
  bin\\kscriptx.ps1 examples\\hello.kts

Native kotlinc (required for compiles) is currently built for Linux.
On Windows, build it with GraalVM on WSL or a Linux host and point
KSCRIPTX_NATIVE_KOTLINC at that install, or use WSL for scripting.
EOF

OUT_ZIP="$ROOT/dist/kscriptx-${VERSION}-windows-amd64.zip"
rm -f "$OUT_ZIP"

make_zip() {
  local stage_base stage_name out
  stage_base="$(dirname "$STAGE")"
  stage_name="$(basename "$STAGE")"
  out="$OUT_ZIP"
  if command -v zip >/dev/null 2>&1; then
    (cd "$stage_base" && zip -rq "$out" "$stage_name")
    return
  fi
  if command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1; then
    local py
    py="$(command -v python3 || command -v python)"
    "$py" - "$stage_base" "$stage_name" "$out" <<'PY'
import os, sys, zipfile
base, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
root = os.path.join(base, name)
with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            arc = os.path.relpath(full, base).replace(os.sep, "/")
            zf.write(full, arc)
print(out)
PY
    return
  fi
  echo "Neither zip nor python available to create $OUT_ZIP" >&2
  exit 1
}

make_zip
[[ -f "$OUT_ZIP" ]] || { echo "failed to create $OUT_ZIP" >&2; exit 1; }
echo "Wrote $OUT_ZIP"
