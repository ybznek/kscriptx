#!/usr/bin/env bash
# Assemble a portable kscriptx distribution under dist/.
# Layout mirrors bin/: jar + launchers + lib/ (+ optional native-kotlinc/) under the same directory.
# Usage: ./scripts/package-dist.sh [version]
# Env:
#   NATIVE_DIR   — if set (or ~/.kscriptx/native-kotlinc exists and INCLUDE_NATIVE=1), copy native into dist
#   INCLUDE_NATIVE=1 — include native from NATIVE_DIR / default home install (required for Linux packages)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
VERSION="${VERSION#v}"

INCLUDE_NATIVE="${INCLUDE_NATIVE:-0}"
NATIVE_DIR="${NATIVE_DIR:-}"
if [[ -z "$NATIVE_DIR" && "$INCLUDE_NATIVE" == "1" ]]; then
  for candidate in \
    "${HOME}/.kscriptx/native-kotlinc" \
    "${HOME}/.kscript3/native-kotlinc"; do
    if [[ -x "$candidate/kotlinc-native" || -x "$(readlink -f "$candidate/kotlinc-native" 2>/dev/null || true)" ]]; then
      NATIVE_DIR="$(readlink -f "$candidate")"
      break
    fi
  done
fi

OUT="$ROOT/dist/kscriptx-${VERSION}"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/share/doc/kscriptx"

echo "==> building CLI $VERSION"
(
  cd "$ROOT"
  ./gradlew :cli:build --quiet
)

install -m 0644 "$ROOT/bin/kscriptx.jar" "$OUT/bin/"
cp -a "$ROOT/bin/lib" "$OUT/bin/"
if [[ -d "$ROOT/bin/lib-resolve" ]]; then
  cp -a "$ROOT/bin/lib-resolve" "$OUT/bin/"
fi
if [[ -d "$ROOT/bin/lib-compiler" ]]; then
  cp -a "$ROOT/bin/lib-compiler" "$OUT/bin/"
fi
install -m 0755 "$ROOT/bin/kscriptx" "$OUT/bin/"
install -m 0644 "$ROOT/bin/kscriptx.bat" "$OUT/bin/"
install -m 0644 "$ROOT/bin/kscriptx.ps1" "$OUT/bin/"
if [[ -x "$ROOT/bin/kscriptx-dclient" ]]; then
  install -m 0755 "$ROOT/bin/kscriptx-dclient" "$OUT/bin/"
fi
if [[ -x "$ROOT/bin/kscriptx-coverage" ]]; then
  install -m 0755 "$ROOT/bin/kscriptx-coverage" "$OUT/bin/"
fi
install -m 0644 "$ROOT/README.md" "$OUT/share/doc/kscriptx/"
install -m 0644 "$ROOT/LICENSE" "$OUT/share/doc/kscriptx/"

if [[ -n "$NATIVE_DIR" ]]; then
  NATIVE_DIR="$(readlink -f "$NATIVE_DIR")"
  [[ -x "$NATIVE_DIR/kotlinc-native" ]] || {
    echo "NATIVE_DIR=$NATIVE_DIR is missing kotlinc-native" >&2
    exit 1
  }
  echo "==> bundling native kotlinc from $NATIVE_DIR"
  mkdir -p "$OUT/bin/native-kotlinc"
  cp -a "$NATIVE_DIR"/. "$OUT/bin/native-kotlinc/"
  chmod +x "$OUT/bin/native-kotlinc/kotlinc-native"
fi

printf '%s\n' "$VERSION" >"$OUT/VERSION"

echo "Assembled $OUT"
