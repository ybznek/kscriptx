#!/usr/bin/env bash
# Create a JVM-kotlinc-safe copy of kotlin-compiler-embeddable for native-image *analysis* CP.
# Drops non-JVM backends + jline only (proven safe via K2JVMCompiler smoke).
# Keeps jansi classes (JansiLoader @Substitute handles extract at runtime).
# PathUtil sidecar must remain the FULL embeddable jar — do not install this output as sidecar.
#
# Usage:
#   ./scripts/shrink-embeddable-for-ni.sh IN.jar OUT.jar
set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }

[[ $# -eq 2 ]] || die "usage: $0 IN.jar OUT.jar"
IN="$1"
OUT="$2"
[[ -f "$IN" ]] || die "missing input jar: $IN"

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }
need_cmd jar
need_cmd find
need_cmd rm

TMP="$(mktemp -d "${TMPDIR:-/tmp}/kscriptx-shrink-embed.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

mkdir -p "$TMP/classes"
(cd "$TMP/classes" && jar xf "$IN")

# Safe removals for batch K2JVMCompiler (verified with JVM smoke).
rm -rf \
  "$TMP/classes/org/jetbrains/kotlin/backend/wasm" \
  "$TMP/classes/org/jetbrains/kotlin/backend/konan" \
  "$TMP/classes/org/jetbrains/kotlin/js" \
  "$TMP/classes/org/jetbrains/kotlin/serialization/js" \
  "$TMP/classes/org/jetbrains/kotlin/org/jline"

# Drop broken / unused jline native-image metadata + service providers.
rm -rf "$TMP/classes/META-INF/native-image"
find "$TMP/classes/META-INF" \( -iname '*jline*' -o -path '*/org/jline/*' \) -delete 2>/dev/null || true

mkdir -p "$(dirname "$OUT")"
jar cf "$OUT" -C "$TMP/classes" .

echo "shrink-embeddable-for-ni: $(du -h "$IN" | awk '{print $1}') → $(du -h "$OUT" | awk '{print $1}') ($(basename "$OUT"))"
