#!/usr/bin/env bash
# Trim a full java.base.jar (from jmod extract) for kscriptx native kotlinc.
# Removes module-info + packages not needed for typical Kotlin script compiles.
#
# Usage:
#   ./scripts/trim-java-base.sh input-java.base.jar output-java.base.jar
#   ./scripts/trim-java-base.sh --smoke input.jar   # trim to temp + smoke-test
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE=0
if [[ "${1:-}" == "--smoke" ]]; then
  SMOKE=1
  shift
fi

IN="${1:?usage: trim-java-base.sh [--smoke] <in.jar> [out.jar]}"
OUT="${2:-}"

need() { command -v "$1" >/dev/null || { echo "missing $1" >&2; exit 1; }; }
need jar

WORK="$(mktemp -d "${TMPDIR:-/tmp}/kscriptx-jbase-XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> extracting $(basename "$IN")"
mkdir -p "$WORK/classes"
(
  cd "$WORK/classes"
  jar xf "$IN"
  # module-info ModulePackages breaks when we delete packages
  rm -f module-info.class

  # Validated against hello / jsoup / java.io+nio+net+time+math smokes (see scripts/trim-java-base.sh history).
  # Keep: java.*, most jdk.internal.*, sun.misc/reflect/nio/net, javax.crypto/net/security.
  rm -rf \
    com/sun \
    sun/launcher \
    sun/text \
    sun/util \
    sun/security/tools \
    sun/security/pkcs11 \
    sun/security/krb5 \
    sun/security/jgss \
    sun/security/smartcardio \
    sun/security/provider/certpath \
    sun/security/rsa \
    sun/security/ec \
    sun/security/ssl \
    sun/security/timestamp \
    sun/security/validator \
    sun/security/x509 \
    jdk/internal/org \
    jdk/internal/jimage \
    jdk/internal/jrtfs \
    jdk/internal/foreign \
    jdk/internal/perf \
    jdk/internal/module \
    jdk/internal/loader \
    jdk/internal/platform \
    jdk/internal/event \
    jdk/internal/config \
    jdk/internal/javac \
    2>/dev/null || true
)

if [[ -z "$OUT" ]]; then
  OUT="$WORK/java.base.trimmed.jar"
fi
jar cf "$OUT" -C "$WORK/classes" .
echo "==> wrote $OUT ($(du -h "$OUT" | awk '{print $1}'), was $(du -h "$IN" | awk '{print $1}'))"

if [[ "$SMOKE" == "1" ]]; then
  NATIVE="${KSCRIPTX_NATIVE_KOTLINC:-$HOME/.kscriptx/native-kotlinc}"
  [[ -x "$NATIVE/kotlinc-native" ]] || {
    echo "smoke skipped: no kotlinc-native at $NATIVE" >&2
    exit 0
  }
  STDLIB="$NATIVE/kotlin-home/lib/kotlin-stdlib.jar"
  SRC="$WORK/Smoke.kt"
  cat >"$SRC" <<'EOF'
import java.io.File
import java.nio.file.Files
import java.net.URI
import java.time.Instant
import java.math.BigDecimal
fun main() {
  val f = File.createTempFile("ksx", ".txt")
  f.writeText("hi")
  check(Files.readString(f.toPath()) == "hi")
  check(URI("https://example.com").host == "example.com")
  Instant.now()
  check(BigDecimal("1.5") + BigDecimal.ONE > BigDecimal.ONE)
  println("trim-smoke-ok")
}
EOF
  OUTD="$WORK/out"
  mkdir -p "$OUTD"
  env KSCRIPTX_KOTLIN_COMPILER_JAR="$NATIVE/kotlin-compiler-embeddable.jar" \
    "$NATIVE/kotlinc-native" \
    -kotlin-home "$NATIVE/kotlin-home" \
    -no-jdk \
    -classpath "$OUT:$STDLIB" \
    -d "$OUTD" \
    -jvm-target 17 \
    -no-stdlib \
    -no-reflect \
    -Xdisable-default-scripting-plugin \
    "$SRC"
  [[ -f "$OUTD/SmokeKt.class" ]] || { echo "smoke compile missing class" >&2; exit 1; }
  echo "smoke OK"
fi

# If OUT was in WORK and caller didn't pass out path, copy to stdout path hint
if [[ "${2:-}" == "" && "$SMOKE" == "0" ]]; then
  echo "note: pass an output path to keep the jar: $0 $IN /path/to/java.base.jar" >&2
fi
