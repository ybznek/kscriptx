#!/usr/bin/env bash
# Build Ubuntu/Debian .deb with CLI + bundled native kotlinc.
# Usage: ./scripts/package-deb.sh [version] [native-dir]
# Env:
#   ARCH       — debian arch (default: dpkg --print-architecture)
#   NATIVE_DIR — native kotlinc install (default: 2nd arg or ~/.kscriptx/native-kotlinc)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
NATIVE_DIR="${2:-${NATIVE_DIR:-}}"
ARCH="${ARCH:-}"

if [[ -z "$VERSION" ]]; then
  if compgen -G "$ROOT/dist/kscriptx-*/VERSION" >/dev/null; then
    VERSION="$(cat "$ROOT/dist"/kscriptx-*/VERSION | head -1)"
  else
    VERSION="$(grep -E '^\s*version\s*=' "$ROOT/cli/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
  fi
fi
VERSION="${VERSION#v}"

if [[ -z "$ARCH" ]]; then
  if command -v dpkg >/dev/null 2>&1; then
    ARCH="$(dpkg --print-architecture)"
  else
    case "$(uname -m)" in
      x86_64|amd64) ARCH=amd64 ;;
      aarch64|arm64) ARCH=arm64 ;;
      armv7l|armhf) ARCH=armhf ;;
      *) echo "set ARCH=... explicitly (uname -m=$(uname -m))" >&2; exit 1 ;;
    esac
  fi
fi

if [[ -z "$NATIVE_DIR" ]]; then
  for candidate in \
    "${HOME}/.kscriptx/native-kotlinc" \
    "${HOME}/.kscript3/native-kotlinc" \
    "$ROOT/dist/kscriptx-${VERSION}/bin/native-kotlinc"; do
    if [[ -e "$candidate/kotlinc-native" ]]; then
      NATIVE_DIR="$candidate"
      break
    fi
  done
fi
[[ -n "$NATIVE_DIR" ]] || {
  echo "native kotlinc required for .deb — build with ./scripts/build-native-kotlinc.sh" >&2
  echo "or pass NATIVE_DIR=/path/to/native-kotlinc" >&2
  exit 1
}
NATIVE_DIR="$(readlink -f "$NATIVE_DIR")"
[[ -x "$NATIVE_DIR/kotlinc-native" ]] || {
  echo "missing executable: $NATIVE_DIR/kotlinc-native" >&2
  exit 1
}

need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1" >&2; exit 1; }; }
need_cmd dpkg-deb

DIST="$ROOT/dist/kscriptx-${VERSION}"
if [[ ! -d "$DIST/bin" ]]; then
  INCLUDE_NATIVE=1 NATIVE_DIR="$NATIVE_DIR" "$ROOT/scripts/package-dist.sh" "$VERSION"
elif [[ ! -d "$DIST/bin/native-kotlinc" ]]; then
  echo "==> adding native kotlinc into existing dist"
  mkdir -p "$DIST/bin/native-kotlinc"
  cp -a "$NATIVE_DIR"/. "$DIST/bin/native-kotlinc/"
  chmod +x "$DIST/bin/native-kotlinc/kotlinc-native"
fi

STAGE="$ROOT/dist/deb-stage"
DEB_ROOT="$STAGE/kscriptx_${VERSION}_${ARCH}"
rm -rf "$STAGE"
mkdir -p "$DEB_ROOT/DEBIAN" \
  "$DEB_ROOT/usr/lib/kscriptx" \
  "$DEB_ROOT/usr/bin" \
  "$DEB_ROOT/usr/share/doc/kscriptx"

# Install tree: jar + libs + native-kotlinc next to launcher
cp -a "$DIST/bin/." "$DEB_ROOT/usr/lib/kscriptx/"
# Ensure native is present even if dist was assembled without it
if [[ ! -x "$DEB_ROOT/usr/lib/kscriptx/native-kotlinc/kotlinc-native" ]]; then
  mkdir -p "$DEB_ROOT/usr/lib/kscriptx/native-kotlinc"
  cp -a "$NATIVE_DIR"/. "$DEB_ROOT/usr/lib/kscriptx/native-kotlinc/"
fi
chmod +x "$DEB_ROOT/usr/lib/kscriptx/native-kotlinc/kotlinc-native"
chmod +x "$DEB_ROOT/usr/lib/kscriptx/kscriptx"

install -m 0755 /dev/stdin "$DEB_ROOT/usr/bin/kscriptx" <<'EOF'
#!/usr/bin/env bash
export KSCRIPTX_NATIVE_KOTLINC="${KSCRIPTX_NATIVE_KOTLINC:-/usr/lib/kscriptx/native-kotlinc}"
exec /usr/lib/kscriptx/kscriptx "$@"
EOF

install -m 0644 "$ROOT/README.md" "$DEB_ROOT/usr/share/doc/kscriptx/"
install -m 0644 "$ROOT/LICENSE" "$DEB_ROOT/usr/share/doc/kscriptx/copyright"
cat >"$DEB_ROOT/usr/share/doc/kscriptx/README.Debian" <<EOF
kscriptx for Debian/Ubuntu (${ARCH})
====================================

This package includes the GraalVM native kotlinc under:

  /usr/lib/kscriptx/native-kotlinc

No extra native install step is required. Cache/data still live under
~/.kscriptx (or KSCRIPTX_DIRECTORY).
EOF

INSTALLED_SIZE="$(du -sk "$DEB_ROOT" | awk '{print $1}')"

cat >"$DEB_ROOT/DEBIAN/control" <<EOF
Package: kscriptx
Version: ${VERSION}
Section: devel
Priority: optional
Architecture: ${ARCH}
Maintainer: kscriptx contributors <noreply@users.noreply.github.com>
Installed-Size: ${INSTALLED_SIZE}
Depends: bash, openjdk-17-jre-headless | openjdk-21-jre-headless | java17-runtime-headless | java17-runtime | default-jre-headless
Recommends: openjdk-21-jdk
Homepage: https://github.com/ybznek/kscriptx
Description: Kotlin scripting tool (kscript-compatible)
 kscriptx compiles and runs Kotlin scripts (.kts/.kt) with Maven
 dependencies via Coursier. This package bundles a GraalVM native
 kotlinc for fast compiles.
EOF

OUT_DEB="$ROOT/dist/kscriptx_${VERSION}_${ARCH}.deb"
dpkg-deb --build --root-owner-group "$DEB_ROOT" "$OUT_DEB"
echo "Wrote $OUT_DEB"
