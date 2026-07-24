#!/usr/bin/env bash
# Package an existing native-kotlinc install as a release tarball.
# Usage: ./scripts/package-native-kotlinc.sh [version] [source-dir] [arch]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
SRC="${2:-${HOME}/.kscriptx/native-kotlinc}"
ARCH="${3:-${ARCH:-}}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
VERSION="${VERSION#v}"
SRC="$(readlink -f "$SRC")"

if [[ -z "$ARCH" ]]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    armv7l|armhf) ARCH=armhf ;;
    *) ARCH="$(uname -m)" ;;
  esac
fi

[[ -x "$SRC/kotlinc-native" ]] || {
  echo "native kotlinc not found at $SRC (run ./scripts/build-native-kotlinc.sh first)" >&2
  exit 1
}

STAGE="$ROOT/dist/kscriptx-native-kotlinc-${VERSION}-linux-${ARCH}"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$SRC"/. "$STAGE/"
printf '%s\n' "$VERSION" >"$STAGE/VERSION"
cat >"$STAGE/README.txt" <<EOF
kscriptx native kotlinc ${VERSION} (linux-${ARCH})
=================================================

Usually not needed separately — Debian packages and Linux tarballs already
bundle this under bin/native-kotlinc or /usr/lib/kscriptx/native-kotlinc.

Manual install:
  mkdir -p ~/.kscriptx
  rm -rf ~/.kscriptx/native-kotlinc
  cp -a . ~/.kscriptx/native-kotlinc
  chmod +x ~/.kscriptx/native-kotlinc/kotlinc-native

Or set KSCRIPTX_NATIVE_KOTLINC to this directory.
EOF

OUT="$ROOT/dist/kscriptx-native-kotlinc-${VERSION}-linux-${ARCH}.tar.gz"
rm -f "$OUT"
tar -C "$ROOT/dist" -czf "$OUT" "$(basename "$STAGE")"
echo "Wrote $OUT"
