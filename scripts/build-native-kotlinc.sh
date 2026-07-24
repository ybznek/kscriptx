#!/usr/bin/env bash
# Build a GraalVM native-image of Kotlin's K2JVMCompiler and install it for kscriptx.
#
# Prerequisites:
#   - GraalVM JDK 21+ with native-image on PATH (or via SDKMAN: 21.0.2-graalce)
#   - A JDK with jmods (for java.base.jar); defaults to JAVA_HOME / system OpenJDK 21
#   - kscriptx compiler jars: run `./gradlew :cli:build` first (bin/lib-compiler/)
#
# Usage:
#   ./scripts/build-native-kotlinc.sh
#   INSTALL_DIR=~/.kscriptx/native-kotlinc ./scripts/build-native-kotlinc.sh
#   SKIP_INSTALL=1 ./scripts/build-native-kotlinc.sh   # only build under WORK_DIR
#
# Workarounds baked in (see README):
#   - exclude broken jline / embeddable META-INF/native-image configs
#   - PathUtil @Substitute → sidecar kotlin-compiler-embeddable.jar
#   - -H:+AddAllCharsets
#   - -no-jdk + extracted java.base.jar (JRT / -jdk-home still unsupported)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/scripts/native-kotlinc"
LIB_COMPILER="${LIB_COMPILER:-$ROOT/bin/lib-compiler}"
WORK_DIR="${WORK_DIR:-${TMPDIR:-/tmp}/kscriptx-native-kotlinc-build}"
INSTALL_DIR="${INSTALL_DIR:-${HOME}/.kscriptx/native-kotlinc}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
SMOKE="${SMOKE:-1}"

die() { echo "error: $*" >&2; exit 1; }

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

resolve_graal() {
  if command -v native-image >/dev/null 2>&1; then
    return 0
  fi
  local sdk="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"
  if [[ -x "$sdk/current/bin/native-image" ]]; then
    export JAVA_HOME="$sdk/current"
    export PATH="$JAVA_HOME/bin:$PATH"
    return 0
  fi
  # Prefer a graalce candidate if present
  local d
  for d in "$sdk"/*graal*; do
    if [[ -x "$d/bin/native-image" ]]; then
      export JAVA_HOME="$d"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done
  die "native-image not found. Install GraalVM CE 21+ (e.g. sdk install java 21.0.2-graalce)"
}

resolve_jdk_for_jmods() {
  if [[ -n "${JDK_HOME:-}" && -f "$JDK_HOME/jmods/java.base.jmod" ]]; then
    echo "$JDK_HOME"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/jmods/java.base.jmod" ]]; then
    echo "$JAVA_HOME"
    return
  fi
  for cand in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk-amd64; do
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
  resolve_graal
  need_cmd native-image

  [[ -d "$LIB_COMPILER" ]] || die "missing $LIB_COMPILER — run ./gradlew :cli:build first"
  shopt -s nullglob
  local jars=("$LIB_COMPILER"/*.jar)
  shopt -u nullglob
  [[ ${#jars[@]} -gt 0 ]] || die "no jars in $LIB_COMPILER"

  local compiler_jar=""
  local stdlib_jar=""
  local j
  for j in "${jars[@]}"; do
    case "$(basename "$j")" in
      kotlin-compiler-embeddable-*.jar) compiler_jar="$j" ;;
      kotlin-stdlib-*.jar) stdlib_jar="$j" ;;
    esac
  done
  [[ -n "$compiler_jar" ]] || die "kotlin-compiler-embeddable-*.jar not found in $LIB_COMPILER"
  [[ -n "$stdlib_jar" ]] || die "kotlin-stdlib-*.jar not found in $LIB_COMPILER"

  local jdk
  jdk="$(resolve_jdk_for_jmods)"

  echo "==> work dir: $WORK_DIR"
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR/substitutions/classes" "$WORK_DIR/kotlin-home/lib" "$WORK_DIR/jdk-stub"

  echo "==> compile PathUtil substitution"
  local svm="$JAVA_HOME/lib/svm/builder/svm.jar"
  local pointsto="$JAVA_HOME/lib/svm/builder/pointsto.jar"
  local nib="$JAVA_HOME/lib/svm/builder/native-image-base.jar"
  [[ -f "$svm" ]] || die "svm.jar not found under $JAVA_HOME (is this GraalVM?)"
  javac --class-path "$svm:$pointsto:$nib" \
    -d "$WORK_DIR/substitutions/classes" \
    "$ASSETS/substitutions/src/PathUtilSubstitution.java"

  local cp
  cp=$(printf '%s:' "${jars[@]}")
  cp="${cp%:}:$WORK_DIR/substitutions/classes"

  echo "==> native-image K2JVMCompiler (this can take ~1–2 min and several GB RAM)"
  native-image \
    -cp "$cp" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -o "$WORK_DIR/kotlinc-native" \
    --no-fallback \
    -H:+ReportExceptionStackTraces \
    -H:+UnlockExperimentalVMOptions \
    -H:+AddAllCharsets \
    -H:+AllowIncompleteClasspath \
    --enable-url-protocols=jar,file \
    --exclude-config '.*jline.*' '.*' \
    --exclude-config '.*kotlin-compiler-embeddable.*' 'META-INF/native-image/.*' \
    -H:ConfigurationFileDirectories="$ASSETS/agent-config"

  echo "==> extract java.base.jar from $jdk"
  jmod extract "$jdk/jmods/java.base.jmod" --dir "$WORK_DIR/jdk-stub/java.base"
  jar cf "$WORK_DIR/java.base.jar" -C "$WORK_DIR/jdk-stub/java.base/classes" .

  echo "==> assemble kotlin-home + sidecar"
  cp -f "$compiler_jar" "$WORK_DIR/kotlin-compiler-embeddable.jar"
  cp -f "$stdlib_jar" "$WORK_DIR/kotlin-home/lib/kotlin-stdlib.jar"
  # Optional companions when present
  for j in "${jars[@]}"; do
    case "$(basename "$j")" in
      kotlin-reflect-*.jar) cp -f "$j" "$WORK_DIR/kotlin-home/lib/kotlin-reflect.jar" ;;
      kotlin-script-runtime-*.jar) cp -f "$j" "$WORK_DIR/kotlin-home/lib/" ;;
      kotlin-compiler-embeddable-*.jar)
        cp -f "$j" "$WORK_DIR/kotlin-home/lib/kotlin-compiler-embeddable.jar"
        ;;
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
    "$WORK_DIR/kotlinc-native" \
      -kotlin-home "$WORK_DIR/kotlin-home" \
      -no-jdk \
      -classpath "$stdlib_jar:$WORK_DIR/java.base.jar" \
      -d "$WORK_DIR/smoke/out" \
      -jvm-target 17 \
      -no-stdlib \
      -no-reflect \
      "$WORK_DIR/smoke/Hello.kt"
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
