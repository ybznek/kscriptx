#!/usr/bin/env bash
# Package an existing native-kotlinc install as a release tarball.
# Usage: ./scripts/package-native-kotlinc.sh [version] [source-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
SRC="${2:-${HOME}/.kscriptx/native-kotlinc}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
VERSION="${VERSION#v}"

[[ -x "$SRC/kotlinc-native" ]] || {
  echo "native kotlinc not found at $SRC (run ./scripts/build-native-kotlinc.sh first)" >&2
  exit 1
}

STAGE="$ROOT/dist/kscriptx-native-kotlinc-${VERSION}-linux-amd64"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$SRC"/. "$STAGE/"
printf '%s\n' "$VERSION" >"$STAGE/VERSION"
cat >"$STAGE/README.txt" <<EOF
kscriptx native kotlinc ${VERSION} (linux-amd64)
================================================

Install:
  mkdir -p ~/.kscriptx
  rm -rf ~/.kscriptx/native-kotlinc
  cp -a . ~/.kscriptx/native-kotlinc
  chmod +x ~/.kscriptx/native-kotlinc/kotlinc-native

Or set KSCRIPTX_NATIVE_KOTLINC to this directory.
EOF

OUT="$ROOT/dist/kscriptx-native-kotlinc-${VERSION}-linux-amd64.tar.gz"
rm -f "$OUT"
tar -C "$ROOT/dist" -czf "$OUT" "$(basename "$STAGE")"
echo "Wrote $OUT"
