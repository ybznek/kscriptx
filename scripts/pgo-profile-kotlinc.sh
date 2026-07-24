#!/usr/bin/env bash
# Collect a GraalVM PGO profile for kotlinc-native, then rebuild with --pgo=.
#
# GraalVM CE 21 often lacks PGO; Oracle GraalVM / newer CE builds may support it.
# This script fails with a clear message when instrumentation is unavailable.
#
# Usage:
#   ./scripts/pgo-profile-kotlinc.sh
# Env:
#   INSTALL_DIR  — native install (default ~/.kscriptx/native-kotlinc)
#   PGO_DIR      — where to write default.iprof (default $INSTALL_DIR/pgo)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.kscriptx/native-kotlinc}"
PGO_DIR="${PGO_DIR:-$INSTALL_DIR/pgo}"
WORK="${TMPDIR:-/tmp}/kscriptx-pgo-$$"

die() { echo "error: $*" >&2; exit 1; }

# shellcheck disable=SC1091
source "$ROOT/scripts/resolve-graalvm.sh"
resolve_graalvm || die "native-image not found"

if ! native-image --help 2>&1 | grep -qi pgo; then
  cat >&2 <<'EOF'
PGO flags are not available in this native-image build.

GraalVM CE 25 typically does not ship PGO; use Oracle GraalVM (EE) or a newer
GraalVM release that lists --pgo / --pgo-instrument in `native-image --help`.

For CE, prefer the defaults in build-native-kotlinc.sh instead:
  --gc=epsilon  -O3  -march=x86-64-v3
EOF
  exit 2
fi

echo "==> step 1/3: instrumented native-image (PGO_INSTRUMENT=1)"
rm -rf "$WORK"
PGO_INSTRUMENT=1 SKIP_INSTALL=1 WORK_DIR="$WORK/build" \
  "$ROOT/scripts/build-native-kotlinc.sh"

BIN="$WORK/build/kotlinc-native"
[[ -x "$BIN" ]] || die "instrumented binary missing"

echo "==> step 2/3: representative compiles (writes default.iprof next to the binary)"
STDLIB="$WORK/build/kotlin-home/lib/kotlin-stdlib.jar"
JBASE="$WORK/build/java.base.jar"
SIDECAR="$WORK/build/kotlin-compiler-embeddable.jar"
mkdir -p "$WORK/src" "$WORK/out1" "$WORK/out2" "$WORK/out3"
cat >"$WORK/src/A.kt" <<'EOF'
fun main(args: Array<String>) {
  val xs = args.toList().map { it.length }.sum()
  println("sum=$xs")
}
EOF
cat >"$WORK/src/B.kt" <<'EOF'
import java.io.File
import java.nio.file.Files
import java.time.Instant
fun main() {
  val f = File.createTempFile("pgo", ".txt")
  f.writeText("hello")
  println(Files.readString(f.toPath()))
  println(Instant.now())
}
EOF
cat >"$WORK/src/C.kt" <<'EOF'
fun main() {
  val r = Regex("""\d+""")
  println(r.findAll("a1b22c333").map { it.value }.toList())
}
EOF

run_one() {
  local src=$1 out=$2
  env KSCRIPTX_KOTLIN_COMPILER_JAR="$SIDECAR" \
    "$BIN" -kotlin-home "$WORK/build/kotlin-home" -no-jdk \
    -classpath "$JBASE:$STDLIB" -d "$out" -jvm-target 17 -no-stdlib -no-reflect \
    -Xdisable-default-scripting-plugin "$src"
}

run_one "$WORK/src/A.kt" "$WORK/out1"
run_one "$WORK/src/B.kt" "$WORK/out2"
run_one "$WORK/src/C.kt" "$WORK/out3"

PROF="$(find "$WORK/build" -name '*.iprof' -o -name 'default.iprof' 2>/dev/null | head -1)"
[[ -n "$PROF" && -f "$PROF" ]] || {
  # Graal often writes default.iprof into cwd
  PROF="$(find . "$WORK" -name 'default.iprof' 2>/dev/null | head -1 || true)"
}
[[ -n "${PROF:-}" && -f "$PROF" ]] || die "no .iprof produced — is this an instrumented build?"

mkdir -p "$PGO_DIR"
cp -f "$PROF" "$PGO_DIR/default.iprof"
echo "==> profile: $PGO_DIR/default.iprof"

echo "==> step 3/3: optimized rebuild with --pgo="
PGO=1 PGO_DATA="$PGO_DIR/default.iprof" INSTALL_DIR="$INSTALL_DIR" \
  "$ROOT/scripts/build-native-kotlinc.sh"

echo "PGO build installed to $INSTALL_DIR"
