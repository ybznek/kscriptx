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
cp -a "$DIST/bin" "$DIST/share" "$STAGE/"
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
(
  cd "$ROOT/dist"
  if command -v zip >/dev/null 2>&1; then
    zip -rq "$(basename "$OUT_ZIP")" "$(basename "$STAGE")"
  else
    tar -czf "${OUT_ZIP%.zip}.tar.gz" "$(basename "$STAGE")"
    echo "zip not found; wrote ${OUT_ZIP%.zip}.tar.gz instead" >&2
    exit 0
  fi
)
echo "Wrote $OUT_ZIP"
