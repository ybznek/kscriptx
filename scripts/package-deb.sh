#!/usr/bin/env bash
# Build Ubuntu/Debian .deb from an assembled dist/ tree.
# Usage: ./scripts/package-deb.sh [version]
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

need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1" >&2; exit 1; }; }
need_cmd dpkg-deb

DIST="$ROOT/dist/kscriptx-${VERSION}"
if [[ ! -d "$DIST" ]]; then
  "$ROOT/scripts/package-dist.sh" "$VERSION"
fi

STAGE="$ROOT/dist/deb-stage"
DEB_ROOT="$STAGE/kscriptx_${VERSION}_all"
rm -rf "$STAGE"
mkdir -p "$DEB_ROOT/DEBIAN" \
  "$DEB_ROOT/usr/lib/kscriptx" \
  "$DEB_ROOT/usr/bin" \
  "$DEB_ROOT/usr/share/doc/kscriptx"

# Install tree matches portable bin/ layout (jar + lib next to launcher)
cp -a "$DIST/bin/." "$DEB_ROOT/usr/lib/kscriptx/"
# System launcher: thin wrapper so /usr/bin/kscriptx works
install -m 0755 /dev/stdin "$DEB_ROOT/usr/bin/kscriptx" <<'EOF'
#!/usr/bin/env bash
exec /usr/lib/kscriptx/kscriptx "$@"
EOF

install -m 0644 "$ROOT/README.md" "$DEB_ROOT/usr/share/doc/kscriptx/"
install -m 0644 "$ROOT/LICENSE" "$DEB_ROOT/usr/share/doc/kscriptx/copyright"
cat >"$DEB_ROOT/usr/share/doc/kscriptx/README.Debian" <<EOF
kscriptx for Debian/Ubuntu
==========================

After install, install native kotlinc (required for script compiles):

  # From a GitHub release asset (linux-amd64), or
  # build from source with GraalVM CE 21+:
  #   ./scripts/build-native-kotlinc.sh

Default path: ~/.kscriptx/native-kotlinc
EOF

INSTALLED_SIZE="$(du -sk "$DEB_ROOT" | awk '{print $1}')"

cat >"$DEB_ROOT/DEBIAN/control" <<EOF
Package: kscriptx
Version: ${VERSION}
Section: devel
Priority: optional
Architecture: all
Maintainer: kscriptx contributors <noreply@users.noreply.github.com>
Installed-Size: ${INSTALLED_SIZE}
Depends: bash, openjdk-17-jre-headless | openjdk-21-jre-headless | java17-runtime-headless | java17-runtime | default-jre-headless
Recommends: openjdk-21-jdk
Homepage: https://github.com/kscriptx/kscriptx
Description: Kotlin scripting tool (kscript-compatible)
 kscriptx compiles and runs Kotlin scripts (.kts/.kt) with Maven
 dependencies via Coursier and a GraalVM native kotlinc backend.
EOF

cat >"$DEB_ROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
echo "kscriptx installed. For fast compiles, install native kotlinc under ~/.kscriptx/native-kotlinc"
echo "(see /usr/share/doc/kscriptx/README.Debian)."
EOF
chmod 0755 "$DEB_ROOT/DEBIAN/postinst"

OUT_DEB="$ROOT/dist/kscriptx_${VERSION}_all.deb"
dpkg-deb --build --root-owner-group "$DEB_ROOT" "$OUT_DEB"
echo "Wrote $OUT_DEB"
