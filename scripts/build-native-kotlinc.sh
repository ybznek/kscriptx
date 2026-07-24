#!/usr/bin/env bash
# Build a GraalVM native-image of Kotlin's K2JVMCompiler and install it for kscriptx.
#
# Prerequisites:
#   - Newest GraalVM CE with native-image (auto-resolved / installed via SDKMAN)
#   - A JDK with jmods (for java.base.jar); defaults to JAVA_HOME / system OpenJDK
#   - kscriptx compiler jars: run `./gradlew :cli:build` first (bin/lib-compiler/)
#
# Usage:
#   ./scripts/build-native-kotlinc.sh
#   INSTALL_DIR=~/.kscriptx/native-kotlinc ./scripts/build-native-kotlinc.sh
#   SKIP_INSTALL=1 ./scripts/build-native-kotlinc.sh   # only build under WORK_DIR
#   ENSURE_LATEST_GRAAL=0 ./scripts/build-native-kotlinc.sh  # don't sdk-install
#   GRAALVM_HOME=/path/to/graalvm ./scripts/build-native-kotlinc.sh
#   NI_GC=serial NI_OPT=2 NATIVE_MARCH=native ./scripts/build-native-kotlinc.sh
#   SHRINK_EMBEDDABLE=0 ./scripts/build-native-kotlinc.sh  # skip analysis-CP zip strip
#
# Native-image defaults (GraalVM 25 CE, tuned for one-shot kotlinc):
#   NI_GC=epsilon   — no GC (faster for short-lived process; set serial if you OOM)
#   NI_OPT=3        — -O3 max AOT opts
#   NATIVE_MARCH=x86-64-v3 on amd64 (AVX2+); use native for local, compatibility for oldest CPUs
#   SHRINK_EMBEDDABLE=1 — drop non-JVM backends + jline from analysis CP only (full jar = sidecar)
#
# Workarounds baked in (see README):
#   - exclude broken jline / embeddable META-INF/native-image configs
#   - PathUtil @Substitute → sidecar kotlin-compiler-embeddable.jar
#   - JansiLoader @Substitute → skip /tmp native extract
#   - KotlincReachabilityFeature → PicoContainer + headless AWT/Swing (Graal 25)
#   - -no-jdk + trimmed java.base.jar (scripts/trim-java-base.sh)
#   - optional PGO: ./scripts/pgo-profile-kotlinc.sh (needs GraalVM with --pgo)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/scripts/native-kotlinc"
LIB_COMPILER="${LIB_COMPILER:-$ROOT/bin/lib-compiler}"
WORK_DIR="${WORK_DIR:-${TMPDIR:-/tmp}/kscriptx-native-kotlinc-build}"
INSTALL_DIR="${INSTALL_DIR:-${HOME}/.kscriptx/native-kotlinc}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
SMOKE="${SMOKE:-1}"
SHRINK_EMBEDDABLE="${SHRINK_EMBEDDABLE:-1}"

die() { echo "error: $*" >&2; exit 1; }

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

# shellcheck disable=SC1091
source "$ROOT/scripts/resolve-graalvm.sh"

resolve_jdk_for_jmods() {
  if [[ -n "${JDK_HOME:-}" && -f "$JDK_HOME/jmods/java.base.jmod" ]]; then
    echo "$JDK_HOME"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/jmods/java.base.jmod" ]]; then
    echo "$JAVA_HOME"
    return
  fi
  local cand
  for cand in \
    /usr/lib/jvm/java-25-openjdk-amd64 \
    /usr/lib/jvm/java-21-openjdk-amd64 \
    /usr/lib/jvm/java-21-openjdk \
    /usr/lib/jvm/java-17-openjdk-amd64
  do
    if [[ -f "$cand/jmods/java.base.jmod" ]]; then
      echo "$cand"
      return
    fi
  done
  die "no JDK with jmods/java.base.jmod found (set JDK_HOME)"
}

main() {
  need_cmd javac
  need_cmd jar
  need_cmd jmod
  resolve_graalvm || die "native-image not found (see scripts/resolve-graalvm.sh)"
  need_cmd native-image

  [[ -d "$LIB_COMPILER" ]] || die "missing $LIB_COMPILER — run ./gradlew :cli:build first"
  shopt -s nullglob
  local jars=("$LIB_COMPILER"/*.jar)
  shopt -u nullglob
  [[ ${#jars[@]} -gt 0 ]] || die "no jars in $LIB_COMPILER"

  local compiler_jar=""
  local stdlib_jar=""
  local reflect_jar=""
  local coroutines_jar=""
  local j
  for j in "${jars[@]}"; do
    case "$(basename "$j")" in
      kotlin-compiler-embeddable-*.jar) compiler_jar="$j" ;;
      kotlin-stdlib-*.jar) stdlib_jar="$j" ;;
      kotlin-reflect-*.jar) reflect_jar="$j" ;;
      kotlinx-coroutines-core-jvm-*.jar|kotlinx-coroutines-core-*.jar) coroutines_jar="$j" ;;
    esac
  done
  [[ -n "$compiler_jar" ]] || die "kotlin-compiler-embeddable-*.jar not found in $LIB_COMPILER"
  [[ -n "$stdlib_jar" ]] || die "kotlin-stdlib-*.jar not found in $LIB_COMPILER"
  [[ -n "$reflect_jar" ]] || die "kotlin-reflect-*.jar not found in $LIB_COMPILER (CLI args / ReflectJvmMapping)"
  [[ -n "$coroutines_jar" ]] || die "kotlinx-coroutines-core*.jar not found in $LIB_COMPILER (MockApplication → GlobalScope)"

  local jdk
  jdk="$(resolve_jdk_for_jmods)"

  echo "==> work dir: $WORK_DIR"
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR/substitutions/classes" "$WORK_DIR/kotlin-home/lib" "$WORK_DIR/jdk-stub"

  echo "==> compile PathUtil + Jansi + reachability Feature"
  local svm="$JAVA_HOME/lib/svm/builder/svm.jar"
  local pointsto="$JAVA_HOME/lib/svm/builder/pointsto.jar"
  local nib="$JAVA_HOME/lib/svm/builder/native-image-base.jar"
  [[ -f "$svm" ]] || die "svm.jar not found under $JAVA_HOME (is this GraalVM?)"
  javac --class-path "$svm:$pointsto:$nib" \
    -d "$WORK_DIR/substitutions/classes" \
    "$ASSETS/substitutions/src/PathUtilSubstitution.java" \
    "$ASSETS/substitutions/src/JansiSubstitution.java" \
    "$ASSETS/substitutions/src/KotlincReachabilityFeature.java"

  local analysis_jar="$compiler_jar"
  if [[ "$SHRINK_EMBEDDABLE" == "1" ]]; then
    echo "==> shrink embeddable for analysis CP (full jar stays PathUtil sidecar)"
    chmod +x "$ROOT/scripts/shrink-embeddable-for-ni.sh"
    analysis_jar="$WORK_DIR/kotlin-compiler-embeddable-ni.jar"
    "$ROOT/scripts/shrink-embeddable-for-ni.sh" "$compiler_jar" "$analysis_jar"
  fi

  local cp
  # Slim build CP (both reflect + coroutines are required for a working Graal 25 image):
  #   kotlin-reflect  — CLI argument reflection (ReflectJvmMapping)
  #   kotlinx-coroutines — MockApplication references GlobalScope
  # Drop daemon/build-tools jars. PathUtil sidecar still uses the full embeddable.
  cp="$analysis_jar:$stdlib_jar:$reflect_jar:$coroutines_jar"
  for j in "${jars[@]}"; do
    case "$(basename "$j")" in
      annotations-*.jar) cp="$cp:$j" ;;
    esac
  done
  cp="$cp:$WORK_DIR/substitutions/classes"

  echo "==> native-image K2JVMCompiler (this can take ~1–2 min and several GB RAM)"
  # GraalVM 25 CE knobs (measured on short-lived alloc-heavy microbench):
  #   --gc=epsilon  ~30–40% faster alloc vs serial; smaller image; ideal for one-shot kotlinc
  #   -O3           max AOT optimizations (default is ~O2)
  #   -march=…      x86-64-v3 for release amd64; override with NATIVE_MARCH=native|compatibility|…
  # PGO remains Oracle/EE-only (not in GraalVM CE 25 --help).
  # Builder heap: NATIVE_IMAGE_OPTIONS='-J-Xmx5g' (passed through below).
  local ni_gc="${NI_GC:-epsilon}"
  local ni_opt="${NI_OPT:-3}"
  local ni_march="${NATIVE_MARCH:-}"
  if [[ -z "$ni_march" ]]; then
    case "$(uname -m)" in
      x86_64|amd64) ni_march="x86-64-v3" ;;
      *) ni_march="compatibility" ;;
    esac
  fi
  local extra_ni=()
  # shellcheck disable=SC2206
  extra_ni+=( ${NATIVE_IMAGE_OPTIONS:-} )
  if [[ "${PGO_INSTRUMENT:-0}" == "1" ]]; then
    if ! native-image --help 2>&1 | grep -qi pgo; then
      die "PGO_INSTRUMENT=1 requested but this native-image has no PGO support (see scripts/pgo-profile-kotlinc.sh)"
    fi
    extra_ni+=("--pgo-instrument")
  fi
  if [[ "${PGO:-0}" == "1" ]]; then
    [[ -n "${PGO_DATA:-}" && -f "${PGO_DATA}" ]] || die "PGO=1 requires PGO_DATA=path/to/default.iprof"
    if ! native-image --help 2>&1 | grep -qi pgo; then
      die "PGO=1 requested but this native-image has no PGO support"
    fi
    extra_ni+=("--pgo=$PGO_DATA")
  fi
  echo "    gc=$ni_gc  -O$ni_opt  -march=$ni_march  shrink=$SHRINK_EMBEDDABLE"
  # Options before main class (GraalVM 25 is strict about argument order).
  native-image \
    --no-fallback \
    --gc="$ni_gc" \
    -O"$ni_opt" \
    -march="$ni_march" \
    -H:+ReportExceptionStackTraces \
    -H:+UnlockExperimentalVMOptions \
    -H:+AllowIncompleteClasspath \
    -H:+AddAllCharsets \
    --features=KotlincReachabilityFeature \
    --enable-url-protocols=jar,file \
    --exclude-config '.*jline.*' '.*' \
    --exclude-config '.*kotlin-compiler-embeddable.*' 'META-INF/native-image/.*' \
    -H:ConfigurationFileDirectories="$ASSETS/agent-config" \
    "${extra_ni[@]}" \
    -cp "$cp" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -o "$WORK_DIR/kotlinc-native"

  echo "==> extract + trim java.base.jar from $jdk"
  jmod extract "$jdk/jmods/java.base.jmod" --dir "$WORK_DIR/jdk-stub/java.base"
  jar cf "$WORK_DIR/java.base.full.jar" -C "$WORK_DIR/jdk-stub/java.base/classes" .
  chmod +x "$ROOT/scripts/trim-java-base.sh"
  "$ROOT/scripts/trim-java-base.sh" "$WORK_DIR/java.base.full.jar" "$WORK_DIR/java.base.jar"

  echo "==> assemble kotlin-home + PathUtil sidecar"
  # Sidecar must be the real embeddable jar (extension point XMLs / resources are loaded from it).
  # A tiny stub is NOT enough — Kotlin looks up compiler-cli-root.xml via PathUtil.
  cp -f "$compiler_jar" "$WORK_DIR/kotlin-compiler-embeddable.jar"
  cp -f "$stdlib_jar" "$WORK_DIR/kotlin-home/lib/kotlin-stdlib.jar"
  # Optional companions when present (skip second 58MB copy under kotlin-home/lib)
  for j in "${jars[@]}"; do
    case "$(basename "$j")" in
      kotlin-reflect-*.jar) cp -f "$j" "$WORK_DIR/kotlin-home/lib/kotlin-reflect.jar" ;;
      kotlin-script-runtime-*.jar) cp -f "$j" "$WORK_DIR/kotlin-home/lib/" ;;
    esac
  done

  # Graal may emit shared libs next to the binary; keep them with the install.
  shopt -s nullglob
  local so
  for so in "$WORK_DIR"/lib*.so; do
    : # already in WORK_DIR
  done
  shopt -u nullglob

  if [[ "$SMOKE" == "1" ]]; then
    echo "==> smoke compile"
    mkdir -p "$WORK_DIR/smoke/out"
    cat >"$WORK_DIR/smoke/Hello.kt" <<'EOF'
fun main(args: Array<String>) { println("native-ok ${args.joinToString()}") }
EOF
    # Run next to sidecar jar (PathUtil default) and disable scripting plugin like kscriptx.
    (
      cd "$WORK_DIR"
      ./kotlinc-native \
        -kotlin-home ./kotlin-home \
        -no-jdk \
        -classpath "./kotlin-home/lib/kotlin-stdlib.jar:./java.base.jar" \
        -d ./smoke/out \
        -jvm-target 17 \
        -no-stdlib \
        -no-reflect \
        -Xdisable-default-scripting-plugin \
        ./smoke/Hello.kt
    )
    [[ -f "$WORK_DIR/smoke/out/HelloKt.class" ]] || die "smoke compile produced no HelloKt.class"
    echo "smoke OK"
  fi

  if [[ "$SKIP_INSTALL" == "1" ]]; then
    echo "SKIP_INSTALL=1 — artifacts left in $WORK_DIR"
    exit 0
  fi

  echo "==> install → $INSTALL_DIR"
  mkdir -p "$INSTALL_DIR"
  rm -rf "$INSTALL_DIR"/*
  cp -a "$WORK_DIR/kotlinc-native" "$INSTALL_DIR/"
  cp -a "$WORK_DIR/kotlin-compiler-embeddable.jar" "$INSTALL_DIR/"
  cp -a "$WORK_DIR/java.base.jar" "$INSTALL_DIR/"
  cp -a "$WORK_DIR/kotlin-home" "$INSTALL_DIR/"
  shopt -s nullglob
  for so in "$WORK_DIR"/lib*.so; do
    cp -a "$so" "$INSTALL_DIR/"
  done
  shopt -u nullglob
  chmod +x "$INSTALL_DIR/kotlinc-native"

  echo "Installed native kotlinc:"
  ls -lh "$INSTALL_DIR/kotlinc-native" "$INSTALL_DIR/java.base.jar" "$INSTALL_DIR/kotlin-compiler-embeddable.jar"
  echo "kscriptx will pick this up from $INSTALL_DIR (or KSCRIPTX_NATIVE_KOTLINC)."
}

main "$@"
