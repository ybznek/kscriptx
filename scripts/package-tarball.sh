#!/usr/bin/env bash
# Create portable Unix tarball from dist/ (includes native kotlinc when present).
# Usage: ./scripts/package-tarball.sh [version] [arch]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
ARCH="${2:-${ARCH:-}}"
if [[ -z "$VERSION" ]]; then
  if compgen -G "$ROOT/dist/kscriptx-*/VERSION" >/dev/null; then
    VERSION="$(cat "$ROOT/dist"/kscriptx-*/VERSION | head -1)"
  else
    VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
  fi
fi
VERSION="${VERSION#v}"

if [[ -z "$ARCH" ]]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    armv7l|armhf) ARCH=armhf ;;
    *) ARCH="$(uname -m)" ;;
  esac
fi

DIST="$ROOT/dist/kscriptx-${VERSION}"
if [[ ! -d "$DIST" ]]; then
  INCLUDE_NATIVE=1 "$ROOT/scripts/package-dist.sh" "$VERSION"
fi

if [[ ! -x "$DIST/bin/native-kotlinc/kotlinc-native" ]]; then
  echo "warning: dist has no native-kotlinc; tarball will require a separate native install" >&2
fi

OUT_TGZ="$ROOT/dist/kscriptx-${VERSION}-linux-${ARCH}.tar.gz"
rm -f "$OUT_TGZ"
tar -C "$ROOT/dist" -czf "$OUT_TGZ" "kscriptx-${VERSION}"
echo "Wrote $OUT_TGZ"
