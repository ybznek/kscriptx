#!/usr/bin/env bash
# Compare kscript vs kscriptx across cold / warm / warm-daemon phases.
#
# Usage:
#   ./scripts/bench-compare.sh [out-dir]
#
# Env / flags:
#   KSCRIPT / --kscript PATH     classic kscript binary (optional if --skip-kscript)
#   KSCRIPTX / --kscriptx PATH   default: ./bin/kscriptx
#   --skip-kscript               measure kscriptx only
#   --skip-daemon                skip kscriptx daemon warm phase
#   --warm-runs N                timed warm samples (default 5; median reported)
#   --cases LIST                 comma ids (default: hello-nodeps,hello,include,multi)
#                                available: hello-nodeps,hello,include,multi,ffi
#   --no-canvas                  do not regenerate the Cursor canvas after JSON
#   --canvas PATH                canvas output (default: Cursor project canvases/)
#   --no-readme                  do not update README.md benchmark section
#   CANVAS_DIR                   override canvas directory
#   UPDATE_README=0              same as --no-readme
#
# Outputs (under out-dir):
#   compare.json   machine-readable results (input to gen-bench-canvas.py)
#   compare.md     markdown table summary
#
# Re-run anytime; previous out-dir files are overwritten.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/reports/perf-compare"

KSCRIPT_BIN="${KSCRIPT:-}"
KSCRIPTX_BIN="${KSCRIPTX:-$ROOT/bin/kscriptx}"
SKIP_KSCRIPT=0
SKIP_DAEMON=0
WARM_RUNS=5
CASE_IDS="hello-nodeps,hello,include,multi"
GEN_CANVAS=1
UPDATE_README=1
CANVAS_OUT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --kscript) KSCRIPT_BIN="$2"; shift 2 ;;
    --kscriptx) KSCRIPTX_BIN="$2"; shift 2 ;;
    --skip-kscript) SKIP_KSCRIPT=1; shift ;;
    --skip-daemon) SKIP_DAEMON=1; shift ;;
    --warm-runs) WARM_RUNS="$2"; shift 2 ;;
    --cases) CASE_IDS="$2"; shift 2 ;;
    --no-canvas) GEN_CANVAS=0; shift ;;
    --no-readme) UPDATE_README=0; shift ;;
    --canvas) CANVAS_OUT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    -*)
      echo "Unknown arg: $1" >&2
      exit 2
      ;;
    *)
      OUT="$1"
      shift
      ;;
  esac
done

mkdir -p "$OUT"
LOG="$OUT/bench-compare.log"
: >"$LOG"

if [[ ! -x "$KSCRIPTX_BIN" ]]; then
  echo "kscriptx not found at $KSCRIPTX_BIN — build with: ./gradlew :cli:build" >&2
  exit 1
fi

if [[ "$SKIP_KSCRIPT" -eq 0 ]]; then
  if [[ -z "$KSCRIPT_BIN" ]]; then
    KSCRIPT_BIN="$(command -v kscript || true)"
  fi
  if [[ -z "$KSCRIPT_BIN" || ! -x "$KSCRIPT_BIN" ]]; then
    echo "kscript not found. Install classic kscript, or pass --kscript PATH / --skip-kscript." >&2
    exit 1
  fi
fi

NATIVE_ROOT="${KSCRIPTX_NATIVE_KOTLINC:-$HOME/.kscriptx/native-kotlinc}"
export KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/kscript-compare.XXXXXX")"
DAEMON_PID=""
cleanup() {
  if [[ -n "${DAEMON_PID:-}" ]]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

KX_HOME="$WORK/kscriptx-home"
KS_CACHE="$WORK/kscript-cache"
mkdir -p "$KX_HOME" "$KS_CACHE"

log() { printf '[bench-compare] %s\n' "$*" | tee -a "$LOG" >&2; }

# --- case catalog (id|label|relpath|args|description) ---
case_row() {
  case "$1" in
    hello-nodeps)
      printf '%s\n' "hello-nodeps|No dependencies|examples/hello-nodeps.kts||Minimal println; isolates launcher + compile/cache overhead."
      ;;
    hello)
      printf '%s\n' "hello|Maven dependency (jsoup)|examples/hello.kts||Single @DependsOn; cold includes resolve+compile, warm is cache hit."
      ;;
    include)
      printf '%s\n' "include|@file:Import helper|examples/include_demo.kts||Main script plus imported utils.kt."
      ;;
    multi)
      printf '%s\n' "multi|Multi-file imports|examples/multi/main.kts|3 1 4 1 5 9|Several @Import files + script args."
      ;;
    ffi)
      printf '%s\n' "ffi|Panama FFI (libc)|examples/ffi-libc.kts||Needs JDK 22+ native access; may be unsupported on classic kscript."
      ;;
    *)
      echo "Unknown case id: $1" >&2
      return 1
      ;;
  esac
}

ms_run() {
  local start end out rc
  start="$(date +%s%N)"
  set +e
  out="$("$@" >/dev/null 2>"$WORK/last-err.txt")"
  rc=$?
  set -e
  end="$(date +%s%N)"
  if [[ "$rc" -ne 0 ]]; then
    log "FAIL ($rc): $*"
    sed 's/^/  | /' "$WORK/last-err.txt" | tee -a "$LOG" >&2 || true
    return "$rc"
  fi
  awk -v s="$start" -v e="$end" 'BEGIN { printf "%.2f", (e - s) / 1000000 }'
}

median_n() {
  local n="$1"; shift
  local -a samples=()
  local i s
  for ((i = 0; i < n; i++)); do
    s="$(ms_run "$@")" || return $?
    samples+=("$s")
  done
  printf '%s\n' "${samples[@]}" | sort -n | awk -v n="$n" '
    { a[NR]=$1 }
    END {
      if (n % 2) print a[int(n/2)+1]
      else printf "%.2f", (a[n/2] + a[n/2+1]) / 2
    }'
}

wipe_kscriptx_cache() {
  rm -rf "$KX_HOME"
  mkdir -p "$KX_HOME"
}

wipe_kscript_cache() {
  rm -rf "$KS_CACHE"
  mkdir -p "$KS_CACHE"
  # Classic kscript 4.x defaults to ~/.cache/kscript when KSCRIPT_CACHE_DIR is unset;
  # with the env set below, only KS_CACHE is used.
  rm -rf "${HOME}/.cache/kscript" 2>/dev/null || true
}

run_kscriptx() {
  # $@ = script + args; uses current KSCRIPTX_DIRECTORY / daemon flags
  env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
    "$KSCRIPTX_BIN" "$@"
}

run_kscript() {
  # Do not override HOME — wrappers and SDKMAN paths often depend on it.
  env KSCRIPT_CACHE_DIR="$KS_CACHE" \
    "$KSCRIPT_BIN" "$@"
}

start_daemon() {
  [[ "$SKIP_DAEMON" -eq 1 ]] && return 1
  local bin_dir client cp
  bin_dir="$(cd "$(dirname "$KSCRIPTX_BIN")" && pwd)"
  client="$bin_dir/kscriptx-dclient"
  [[ -x "$client" ]] || return 1
  cp="$bin_dir/kscriptx.jar"
  if [[ -d "$bin_dir/lib" ]]; then
    cp="$cp:$bin_dir/lib/*"
  fi
  mkdir -p "$KX_HOME"
  env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=0 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
    java -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -cp "$cp" io.kscriptx.MainKt --daemon-server \
    >"$WORK/daemon.log" 2>&1 &
  DAEMON_PID=$!
  local i
  for i in $(seq 1 80); do
    [[ -e "$KX_HOME/daemon/sock" || -f "$KX_HOME/daemon/port" ]] && return 0
    sleep 0.05
  done
  log "daemon failed to start; see $WORK/daemon.log"
  kill "$DAEMON_PID" 2>/dev/null || true
  DAEMON_PID=""
  return 1
}

append_result() {
  # Args via env: BENCH_ID LABEL SCRIPT ARGS_JSON DESC \
  #   K_COLD K_WARM KX_COLD KX_WARM KX_DAEMON ERR
  python3 - "$RESULTS_JSONL" <<'PY'
import json, os, sys
path = sys.argv[1]

def num(key):
    v = os.environ.get(key, "")
    if not v:
        return None
    return float(v)

rec = {
    "id": os.environ["BENCH_ID"],
    "label": os.environ.get("BENCH_LABEL", ""),
    "script": os.environ.get("BENCH_SCRIPT", ""),
    "args": json.loads(os.environ.get("BENCH_ARGS_JSON", "[]")),
    "description": os.environ.get("BENCH_DESC", ""),
    "phases": {
        "cold": {
            "kscript_ms": num("BENCH_K_COLD"),
            "kscriptx_ms": num("BENCH_KX_COLD"),
        },
        "warm": {
            "kscript_ms": num("BENCH_K_WARM"),
            "kscriptx_ms": num("BENCH_KX_WARM"),
        },
        "warm_daemon": {
            "kscript_ms": None,
            "kscriptx_ms": num("BENCH_KX_DAEMON"),
        },
    },
    "error": os.environ.get("BENCH_ERR") or None,
}
with open(path, "a", encoding="utf-8") as f:
    f.write(json.dumps(rec) + "\n")
PY
}

stop_daemon() {
  if [[ -n "${DAEMON_PID:-}" ]]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
    DAEMON_PID=""
  fi
  rm -f "$KX_HOME/daemon/port" "$KX_HOME/daemon/pid" 2>/dev/null || true
  rm -f "$KX_HOME/daemon/sock" 2>/dev/null || true
}

tool_version() {
  local bin="$1"
  set +e
  "$bin" --version 2>&1 | head -1
  set -e
}

mkdir -p "$WORK/kscript-fake-home"
KSCRIPTX_VER="$(tool_version "$KSCRIPTX_BIN" || true)"
KSCRIPT_VER=""
if [[ "$SKIP_KSCRIPT" -eq 0 ]]; then
  KSCRIPT_VER="$(tool_version "$KSCRIPT_BIN" || true)"
fi

NATIVE_YES=false
[[ -x "$NATIVE_ROOT/kotlinc-native" ]] && NATIVE_YES=true

HOST_FILE="$WORK/host.json"
python3 - <<'PY' >"$HOST_FILE"
import json, platform, os, shutil
print(json.dumps({
  "os": platform.platform(),
  "machine": platform.machine(),
  "python": platform.python_version(),
  "java": shutil.which("java") or "",
  "java_version": os.popen("java -version 2>&1 | head -1").read().strip(),
}))
PY

RESULTS_JSONL="$WORK/results.jsonl"
: >"$RESULTS_JSONL"

IFS=',' read -r -a CASE_ARR <<<"$CASE_IDS"

log "kscriptx=$KSCRIPTX_BIN ($KSCRIPTX_VER)"
if [[ "$SKIP_KSCRIPT" -eq 0 ]]; then
  log "kscript=$KSCRIPT_BIN ($KSCRIPT_VER)"
else
  log "kscript=skipped"
fi
log "warm_runs=$WARM_RUNS cases=${CASE_IDS}"
log "native_kotlinc=$NATIVE_YES ($NATIVE_ROOT)"

for cid in "${CASE_ARR[@]}"; do
  cid="$(echo "$cid" | xargs)"
  [[ -z "$cid" ]] && continue
  row="$(case_row "$cid")"
  IFS='|' read -r id label relpath args desc <<<"$row"
  script="$ROOT/$relpath"
  if [[ ! -f "$script" ]]; then
    log "skip $id: missing $script"
    python3 -c "import json; print(json.dumps({'id':'$id','error':'missing script'}))" >>"$RESULTS_JSONL"
    continue
  fi

  # shellcheck disable=SC2206
  script_args=($args)

  log "=== case $id ($label) ==="

  k_cold=""; k_warm=""; kx_cold=""; kx_warm=""; kx_daemon=""
  err=""

  # --- classic kscript ---
  if [[ "$SKIP_KSCRIPT" -eq 0 ]]; then
    wipe_kscript_cache
    if k_cold="$(ms_run run_kscript "$script" "${script_args[@]}")"; then
      if ! k_warm="$(median_n "$WARM_RUNS" run_kscript "$script" "${script_args[@]}")"; then
        err="${err}kscript warm failed; "
        k_warm=""
      fi
    else
      err="${err}kscript cold failed; "
      k_cold=""
    fi
    log "  kscript cold=${k_cold:-na} warm=${k_warm:-na}"
  fi

  # --- kscriptx no-daemon ---
  export KSCRIPTX_DAEMON=0
  wipe_kscriptx_cache
  if kx_cold="$(ms_run env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=0 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
      "$KSCRIPTX_BIN" --no-daemon "$script" "${script_args[@]}")"; then
    if ! kx_warm="$(median_n "$WARM_RUNS" env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=0 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
        "$KSCRIPTX_BIN" --no-daemon "$script" "${script_args[@]}")"; then
      err="${err}kscriptx warm failed; "
      kx_warm=""
    fi
  else
    err="${err}kscriptx cold failed; "
    kx_cold=""
  fi
  log "  kscriptx cold=${kx_cold:-na} warm=${kx_warm:-na}"

  # --- kscriptx daemon warm (reuse cache from warm phase) ---
  if [[ "$SKIP_DAEMON" -eq 0 && -n "$kx_warm" ]]; then
    stop_daemon
    if start_daemon; then
      if env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=1 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
          "$KSCRIPTX_BIN" "$script" "${script_args[@]}" >/dev/null 2>>"$LOG"; then
        if ! kx_daemon="$(median_n "$WARM_RUNS" env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=1 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
            "$KSCRIPTX_BIN" "$script" "${script_args[@]}")"; then
          err="${err}kscriptx daemon warm failed; "
          kx_daemon=""
        fi
      else
        err="${err}kscriptx daemon prime failed; "
      fi
      stop_daemon
    else
      err="${err}daemon unavailable; "
    fi
    log "  kscriptx daemon_warm=${kx_daemon:-na}"
  fi

  export BENCH_ID="$id"
  export BENCH_LABEL="$label"
  export BENCH_SCRIPT="$relpath"
  BENCH_ARGS_JSON="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' ${script_args[@]+"${script_args[@]}"})"
  export BENCH_ARGS_JSON  export BENCH_DESC="$desc"
  export BENCH_K_COLD="${k_cold:-}"
  export BENCH_K_WARM="${k_warm:-}"
  export BENCH_KX_COLD="${kx_cold:-}"
  export BENCH_KX_WARM="${kx_warm:-}"
  export BENCH_KX_DAEMON="${kx_daemon:-}"
  export BENCH_ERR="$err"
  append_result
done

GENERATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
JSON_OUT="$OUT/compare.json"
MD_OUT="$OUT/compare.md"

TOOLS_FILE="$WORK/tools.json"
export TOOLS_FILE
export BENCH_TOOLS_KSCRIPT_BIN="$KSCRIPT_BIN"
export BENCH_TOOLS_KSCRIPT_VER="$KSCRIPT_VER"
export BENCH_TOOLS_KSCRIPTX_BIN="$KSCRIPTX_BIN"
export BENCH_TOOLS_KSCRIPTX_VER="$KSCRIPTX_VER"
export BENCH_TOOLS_NATIVE_ROOT="$NATIVE_ROOT"
export BENCH_TOOLS_SKIP_KSCRIPT="$SKIP_KSCRIPT"
export BENCH_TOOLS_NATIVE_YES="$NATIVE_YES"
python3 - <<'PY'
import json, os
from pathlib import Path
Path(os.environ["TOOLS_FILE"]).write_text(json.dumps({
  "kscript": {
    "path": os.environ.get("BENCH_TOOLS_KSCRIPT_BIN", ""),
    "version": os.environ.get("BENCH_TOOLS_KSCRIPT_VER", ""),
    "skipped": os.environ.get("BENCH_TOOLS_SKIP_KSCRIPT") == "1",
  },
  "kscriptx": {
    "path": os.environ.get("BENCH_TOOLS_KSCRIPTX_BIN", ""),
    "version": os.environ.get("BENCH_TOOLS_KSCRIPTX_VER", ""),
    "native_kotlinc": os.environ.get("BENCH_TOOLS_NATIVE_YES") == "true",
    "native_kotlinc_path": os.environ.get("BENCH_TOOLS_NATIVE_ROOT", ""),
  },
}, indent=2) + "\n", encoding="utf-8")
PY

python3 - "$JSON_OUT" "$RESULTS_JSONL" "$HOST_FILE" "$TOOLS_FILE" "$GENERATED_AT" "$WARM_RUNS" <<'PY'
import json, sys
from pathlib import Path

out = Path(sys.argv[1])
rows = []
with open(sys.argv[2], encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line:
            rows.append(json.loads(line))

warm_runs = int(sys.argv[6])
doc = {
  "generated_at": sys.argv[5],
  "warm_runs": warm_runs,
  "phases_help": {
    "cold": "Empty tool cache; shared Maven/Coursier local repos may already be warm. Measures resolve+compile+run.",
    "warm": f"Median of {warm_runs} cache-hit runs without the kscriptx daemon.",
    "warm_daemon": "kscriptx only: median cache-hit runs through the persistent JVM daemon after one prime.",
  },
  "host": json.loads(Path(sys.argv[3]).read_text(encoding="utf-8")),
  "tools": json.loads(Path(sys.argv[4]).read_text(encoding="utf-8")),
  "results": rows,
}
out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
print(out)
PY

python3 - "$JSON_OUT" "$MD_OUT" <<'PY'
import json, sys
from pathlib import Path

doc = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
lines = [
  f"# kscript vs kscriptx benchmark",
  "",
  f"Generated: `{doc['generated_at']}`  ",
  f"Warm samples (median): **{doc['warm_runs']}**",
  "",
  "## Phases",
  "",
]
for k, v in doc["phases_help"].items():
    lines.append(f"- **{k}**: {v}")
lines += ["", "## Results (ms)", ""]
headers = [
  "Case", "Phase",
  "kscript", "kscriptx", "kscriptx daemon",
  "speedup vs kscript",
]
lines.append("| " + " | ".join(headers) + " |")
lines.append("|" + "|".join(["---"] * len(headers)) + "|")

def fmt(x):
    return "—" if x is None else f"{x:.2f}"

def speedup(a, b):
    if a is None or b is None or b == 0:
        return "—"
    return f"{a / b:.2f}×"

for r in doc["results"]:
    for phase, label in (("cold", "cold"), ("warm", "warm"), ("warm_daemon", "warm+daemon")):
        p = r.get("phases", {}).get(phase, {})
        ks = p.get("kscript_ms")
        kx = p.get("kscriptx_ms")
        if phase == "warm_daemon":
            # only daemon column meaningful for kscriptx; ks always None
            lines.append(
              f"| {r['id']} | {label} | — | — | {fmt(kx)} | {speedup(ks, kx) if ks else '—'} |"
            )
        else:
            lines.append(
              f"| {r['id']} | {label} | {fmt(ks)} | {fmt(kx)} | — | {speedup(ks, kx)} |"
            )
    if r.get("error"):
        lines.append(f"| {r['id']} | notes | | | | {r['error']} |")

lines += [
  "",
  "## Tools",
  "",
  f"- kscript: `{doc['tools']['kscript']['path']}` ({doc['tools']['kscript'].get('version') or 'n/a'})",
  f"- kscriptx: `{doc['tools']['kscriptx']['path']}` ({doc['tools']['kscriptx'].get('version') or 'n/a'})",
  f"- native kotlinc: {doc['tools']['kscriptx'].get('native_kotlinc')}",
  "",
  f"Source JSON: `{sys.argv[1]}`",
  "",
]
Path(sys.argv[2]).write_text("\n".join(lines), encoding="utf-8")
print(sys.argv[2])
PY

log "Wrote $JSON_OUT"
log "Wrote $MD_OUT"

  if [[ "$GEN_CANVAS" -eq 1 ]]; then
  CANVAS_ARGS=("$JSON_OUT")
  if [[ -n "$CANVAS_OUT" ]]; then
    CANVAS_ARGS+=("$CANVAS_OUT")
  elif [[ -n "${CANVAS_DIR:-}" ]]; then
    CANVAS_ARGS+=("$CANVAS_DIR/kscript-vs-kscriptx.canvas.tsx")
  fi
  python3 "$ROOT/scripts/gen-bench-canvas.py" "${CANVAS_ARGS[@]}"
fi

if [[ "${UPDATE_README:-1}" -eq 1 && "${SKIP_README:-0}" -eq 0 ]]; then
  python3 "$ROOT/scripts/update-readme-bench.py" "$JSON_OUT" "$ROOT/README.md" || true
fi

echo "Done. JSON=$JSON_OUT MD=$MD_OUT"
