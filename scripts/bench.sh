#!/usr/bin/env bash
# CLI performance harness. Prints markdown; writes JSON under the given out dir.
# Usage: ./scripts/bench.sh [out-dir]
# Env: KSCRIPTX=path/to/kscriptx  (default: ./bin/kscriptx)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/build/reports/perf}"
KS="${KSCRIPTX:-$ROOT/bin/kscriptx}"
mkdir -p "$OUT"

if [[ ! -x "$KS" ]]; then
  echo "kscriptx not found at $KS — build with: ./gradlew :cli:build" >&2
  exit 1
fi

# Keep an isolated content cache, but always point at a real native install.
NATIVE_ROOT="${KSCRIPTX_NATIVE_KOTLINC:-$HOME/.kscriptx/native-kotlinc}"
export KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT"

ms_run() {
  local start end out rc
  start="$(date +%s%N)"
  set +e
  out="$("$@" 2>&1)"
  rc=$?
  set -e
  end="$(date +%s%N)"
  if [[ "$rc" -ne 0 ]]; then
    printf '%s\n' "$out" >&2
    echo "[bench] command failed ($rc): $*" >&2
    return "$rc"
  fi
  awk -v s="$start" -v e="$end" 'BEGIN { printf "%.2f", (e - s) / 1000000 }'
}

median3() {
  local a b c
  a="$(ms_run "$@")"
  b="$(ms_run "$@")"
  c="$(ms_run "$@")"
  printf '%s\n' "$a" "$b" "$c" | sort -n | awk 'NR==2{print $1}'
}

HOME_TMP="$(mktemp -d "${TMPDIR:-/tmp}/kscriptx-bench.XXXXXX")"
cleanup() {
  if [[ -n "${DAEMON_PID:-}" ]]; then
    kill "$DAEMON_PID" 2>/dev/null || true
  fi
  rm -rf "$HOME_TMP"
}
trap cleanup EXIT
export KSCRIPTX_DIRECTORY="$HOME_TMP"

# --- no-daemon baseline ---
export KSCRIPTX_DAEMON=0
VERSION_MS="$(ms_run "$KS" --no-daemon --version)"
HELP_MS="$(ms_run "$KS" --no-daemon --help)"

COLD_MS=""
WARM_MS=""
WARM_DAEMON_MS=""
NATIVE="no"
SKIP_REASON=""
SCRIPT="$ROOT/examples/hello.kts"
if [[ -x "$NATIVE_ROOT/kotlinc-native" ]]; then
  NATIVE="yes"
  if COLD_MS="$(ms_run "$KS" --no-daemon "$SCRIPT")"; then
    WARM_MS="$(median3 "$KS" --no-daemon "$SCRIPT")"
  else
    SKIP_REASON="script run failed (see logs)"
    COLD_MS=""
    WARM_MS=""
  fi
else
  SKIP_REASON="native kotlinc not available at $NATIVE_ROOT"
fi

# --- daemon warm path (primary UX after first run) ---
if [[ "$NATIVE" == "yes" && -n "$WARM_MS" && -x "$(dirname "$KS")/kscriptx-dclient" ]]; then
  export KSCRIPTX_DAEMON=1
  CP="$(dirname "$KS")/kscriptx.jar"
  if [[ -d "$(dirname "$KS")/lib" ]]; then
    CP="$CP:$(dirname "$KS")/lib/*"
  fi
  java -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -cp "$CP" io.kscriptx.MainKt --daemon-server \
    >"$HOME_TMP/daemon-bench.log" 2>&1 &
  DAEMON_PID=$!
  for _ in $(seq 1 50); do
    [[ -f "$HOME_TMP/daemon/port" ]] && break
    sleep 0.05
  done
  if [[ -f "$HOME_TMP/daemon/port" ]]; then
    # one priming hit (fills in-memory FastCache + classloader cache)
    "$KS" "$SCRIPT" >/dev/null
    WARM_DAEMON_MS="$(median3 "$KS" "$SCRIPT")"
  fi
  kill "$DAEMON_PID" 2>/dev/null || true
  wait "$DAEMON_PID" 2>/dev/null || true
  DAEMON_PID=""
fi

MD="$OUT/cli-bench.md"
JSON="$OUT/cli-bench.json"

{
  echo "### CLI timings"
  echo
  echo "| Metric | Value |"
  echo "|---|---:|"
  echo "| \`kscriptx --version\` (no daemon) | ${VERSION_MS} ms |"
  echo "| \`kscriptx --help\` (no daemon) | ${HELP_MS} ms |"
  if [[ -n "$COLD_MS" && -n "$WARM_MS" ]]; then
    echo "| Cold run (\`examples/hello.kts\`) | ${COLD_MS} ms |"
    echo "| Warm/cache hit (no daemon) | ${WARM_MS} ms |"
    if [[ -n "$WARM_DAEMON_MS" ]]; then
      echo "| Warm/cache hit (daemon) | ${WARM_DAEMON_MS} ms |"
    fi
  else
    echo "| Cold/warm script run | _skipped (${SKIP_REASON})_ |"
  fi
  echo
  echo "Cache home for this run: \`$KSCRIPTX_DIRECTORY\`"
  echo "Native kotlinc: \`$NATIVE_ROOT\`"
  echo
} | tee "$MD"

json_num() {
  if [[ -n "${1:-}" ]]; then
    printf '%s' "$1"
  else
    printf 'null'
  fi
}

{
  printf '{\n'
  printf '  "version_ms": %s,\n' "$(json_num "$VERSION_MS")"
  printf '  "help_ms": %s,\n' "$(json_num "$HELP_MS")"
  printf '  "cold_hello_ms": %s,\n' "$(json_num "$COLD_MS")"
  printf '  "warm_hello_ms": %s,\n' "$(json_num "$WARM_MS")"
  printf '  "warm_daemon_hello_ms": %s,\n' "$(json_num "$WARM_DAEMON_MS")"
  if [[ "$NATIVE" == "yes" ]]; then
    printf '  "native_kotlinc": true\n'
  else
    printf '  "native_kotlinc": false\n'
  fi
  printf '}\n'
} >"$JSON"

echo "Wrote $MD and $JSON"
