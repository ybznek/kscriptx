#!/usr/bin/env bash
# CLI performance harness. Prints markdown; writes JSON under the given out dir.
# Usage: ./scripts/bench.sh [out-dir]
# Env: KSCRIPTX=path/to/kscriptx  (default: ./bin/kscriptx)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/build/reports/perf}"
KS="${KSCRIPTX:-$ROOT/bin/kscriptx}"
mkdir -p "$OUT"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 1; }; }
need python3

if [[ ! -x "$KS" ]]; then
  echo "kscriptx not found at $KS — build with: ./gradlew :cli:build" >&2
  exit 1
fi

# Keep an isolated content cache, but always point at a real native install.
NATIVE_ROOT="${KSCRIPTX_NATIVE_KOTLINC:-$HOME/.kscriptx/native-kotlinc}"
export KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT"

ms_run() {
  # prints milliseconds as float on stdout; fails if command fails
  python3 - "$@" <<'PY'
import subprocess, sys, time
cmd = sys.argv[1:]
t0 = time.perf_counter()
r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
dt = (time.perf_counter() - t0) * 1000.0
if r.returncode != 0:
    sys.stderr.write(r.stdout)
    sys.stderr.write(f"\n[bench] command failed ({r.returncode}): {' '.join(cmd)}\n")
    sys.exit(r.returncode)
print(f"{dt:.2f}")
PY
}

HOME_TMP="$(mktemp -d "${TMPDIR:-/tmp}/kscriptx-bench.XXXXXX")"
trap 'rm -rf "$HOME_TMP"' EXIT
export KSCRIPTX_DIRECTORY="$HOME_TMP"

VERSION_MS="$(ms_run "$KS" --version)"
HELP_MS="$(ms_run "$KS" --help)"

COLD_MS=""
WARM_MS=""
NATIVE="no"
SKIP_REASON=""
if [[ -x "$NATIVE_ROOT/kotlinc-native" ]]; then
  NATIVE="yes"
  SCRIPT="$ROOT/examples/hello.kts"
  if COLD_MS="$(ms_run "$KS" "$SCRIPT")"; then
    WARM_MS="$(ms_run "$KS" "$SCRIPT")"
  else
    SKIP_REASON="script run failed (see logs)"
    COLD_MS=""
    WARM_MS=""
    NATIVE="yes"
  fi
else
  SKIP_REASON="native kotlinc not available at $NATIVE_ROOT"
fi

MD="$OUT/cli-bench.md"
JSON="$OUT/cli-bench.json"

{
  echo "### CLI timings"
  echo
  echo "| Metric | Value |"
  echo "|---|---:|"
  echo "| \`kscriptx --version\` | ${VERSION_MS} ms |"
  echo "| \`kscriptx --help\` | ${HELP_MS} ms |"
  if [[ -n "$COLD_MS" && -n "$WARM_MS" ]]; then
    echo "| Cold run (\`examples/hello.kts\`) | ${COLD_MS} ms |"
    echo "| Warm/cache hit | ${WARM_MS} ms |"
  else
    echo "| Cold/warm script run | _skipped (${SKIP_REASON})_ |"
  fi
  echo
  echo "Cache home for this run: \`$KSCRIPTX_DIRECTORY\`"
  echo "Native kotlinc: \`$NATIVE_ROOT\`"
  echo
} | tee "$MD"

python3 - "$JSON" "$VERSION_MS" "$HELP_MS" "$COLD_MS" "$WARM_MS" "$NATIVE" <<'PY'
import json, sys
path, ver, help_, cold, warm, native = sys.argv[1:]
def f(x):
    try: return float(x) if x else None
    except: return None
data = {
  "version_ms": f(ver),
  "help_ms": f(help_),
  "cold_hello_ms": f(cold),
  "warm_hello_ms": f(warm),
  "native_kotlinc": native == "yes",
}
with open(path, "w") as fh:
    json.dump(data, fh, indent=2)
    fh.write("\n")
PY

echo "Wrote $MD and $JSON"
