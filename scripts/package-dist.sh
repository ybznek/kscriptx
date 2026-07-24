#!/usr/bin/env bash
# Assemble a portable kscriptx distribution under dist/.
# Layout mirrors bin/: jar + launchers + lib/ + lib-compiler/ under the same directory.
# Usage: ./scripts/package-dist.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
VERSION="${VERSION#v}"

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
if [[ -d "$ROOT/bin/lib-compiler" ]]; then
  cp -a "$ROOT/bin/lib-compiler" "$OUT/bin/"
fi
install -m 0755 "$ROOT/bin/kscriptx" "$OUT/bin/"
install -m 0644 "$ROOT/bin/kscriptx.bat" "$OUT/bin/"
install -m 0644 "$ROOT/bin/kscriptx.ps1" "$OUT/bin/"
install -m 0644 "$ROOT/README.md" "$OUT/share/doc/kscriptx/"
install -m 0644 "$ROOT/LICENSE" "$OUT/share/doc/kscriptx/"

printf '%s\n' "$VERSION" >"$OUT/VERSION"

echo "Assembled $OUT"
