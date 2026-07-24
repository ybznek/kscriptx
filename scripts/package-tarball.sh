#!/usr/bin/env bash
# Create portable Unix tarball from dist/.
# Usage: ./scripts/package-tarball.sh [version]
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

OUT_TGZ="$ROOT/dist/kscriptx-${VERSION}-linux-amd64.tar.gz"
rm -f "$OUT_TGZ"
tar -C "$ROOT/dist" -czf "$OUT_TGZ" "kscriptx-${VERSION}"
echo "Wrote $OUT_TGZ"
