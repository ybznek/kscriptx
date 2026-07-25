#!/usr/bin/env bash
# Compare kscript vs kscriptx across cold / after-change / warm / warm-daemon phases.
#
# Usage:
#   ./scripts/bench-compare.sh [out-dir]
#
# Env / flags:
#   KSCRIPT / --kscript PATH     classic kscript binary (optional if --skip-kscript)
#   KSCRIPT_JAVA_HOME            JDK for classic kscript (use ≤21 if default JDK is 25+)
#   KSCRIPTX / --kscriptx PATH   default: ./bin/kscriptx
#   --skip-kscript               measure kscriptx only
#   --skip-daemon                skip kscriptx daemon warm phase
#   --warm-runs N                timed warm/daemon samples (default 5; median reported)
#   --cases LIST                 comma ids, or "default" / "all"
#                                default/all: hello-nodeps,hello,include,multi,cpu,chatty,
#                                  many-deps,coroutines,ktor-cp,large,kt-file
#                                extra: ffi (kscriptx / JDK 22+ only)
#   --list-cases                 print case catalog and exit
#   --no-canvas                  do not regenerate the Cursor canvas after JSON
#   --canvas PATH                canvas output (default: Cursor project canvases/)
#   --no-readme                  do not update README.md benchmark section
#   CANVAS_DIR                   override canvas directory
#   UPDATE_README=0              same as --no-readme
#
# Phases (all times in milliseconds):
#   cold          First run, empty tool cache (very cold)
#   after_change  Second run after a tiny script edit (must recompile; deps may be warm)
#   warm          Unchanged script, cache hit, new process each sample
#   warm_daemon   Same as warm, but via kscriptx persistent JVM daemon
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
CASE_IDS="default"
LIST_CASES=0
GEN_CANVAS=1
UPDATE_README=1
CANVAS_OUT=""

DEFAULT_CASES="hello-nodeps,hello,include,multi,cpu,chatty,many-deps,coroutines,ktor-cp,large,kt-file"
ALL_CASES="hello-nodeps,hello,include,multi,cpu,chatty,many-deps,coroutines,ktor-cp,large,kt-file"
# ffi is kscriptx-only (Panama allocateFrom needs JDK 22+); run via --cases ffi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --kscript) KSCRIPT_BIN="$2"; shift 2 ;;
    --kscriptx) KSCRIPTX_BIN="$2"; shift 2 ;;
    --skip-kscript) SKIP_KSCRIPT=1; shift ;;
    --skip-daemon) SKIP_DAEMON=1; shift ;;
    --warm-runs) WARM_RUNS="$2"; shift 2 ;;
    --cases) CASE_IDS="$2"; shift 2 ;;
    --list-cases) LIST_CASES=1; shift ;;
    --no-canvas) GEN_CANVAS=0; shift ;;
    --no-readme) UPDATE_README=0; shift ;;
    --canvas) CANVAS_OUT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,35p' "$0"
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
    cpu)
      printf '%s\n' "cpu|CPU loop (no deps)|examples/bench/cpu-loop.kts||Tight arithmetic loop; warm path stresses runtime more than compile."
      ;;
    chatty)
      printf '%s\n' "chatty|Chatty stdout|examples/bench/chatty.kts||500 println lines; exercises daemon stdout framing / IPC."
      ;;
    many-deps)
      printf '%s\n' "many-deps|Several Maven deps|examples/bench/many-deps.kts||gson + commons-lang3 + okhttp; cold stresses multi-artifact resolve."
      ;;
    coroutines)
      printf '%s\n' "coroutines|kotlinx-coroutines|examples/bench/coroutines.kts||Coroutines on classpath + tiny runBlocking."
      ;;
    ktor-cp)
      printf '%s\n' "ktor-cp|Ktor classpath only|examples/bench/ktor-classpath.kts||Ktor client/http jars without starting a server (no network)."
      ;;
    entrypoint)
      echo "Case 'entrypoint' was removed; use kt-file (examples/bench/PlainMain.kt)." >&2
      return 1
      ;;
    large)
      printf '%s\n' "large|Larger source (no deps)|examples/bench/large-source.kts||More Kotlin source for colder K2 compile; still exits immediately."
      ;;
    ffi)
      printf '%s\n' "ffi|Panama FFI (libc)|examples/ffi-libc.kts||kscriptx/JDK 22+ only; classic kscript cannot compile allocateFrom."
      ;;
    kt-file)
      printf '%s\n' "kt-file|Plain .kt entry|examples/bench/PlainMain.kt|bench|Run a .kt file with main() directly (no .kts wrapper)."
      ;;
    *)
      echo "Unknown case id: $1" >&2
      return 1
      ;;
  esac
}

list_cases() {
  echo "id	label	script	notes"
  local id
  IFS=',' read -r -a all <<<"$ALL_CASES"
  for id in "${all[@]}"; do
    local row label relpath args desc
    row="$(case_row "$id")"
    IFS='|' read -r id label relpath args desc <<<"$row"
    printf '%s\t%s\t%s\t%s\n' "$id" "$label" "$relpath" "$desc"
  done
  echo
  echo "default: $DEFAULT_CASES"
  echo "all:     $ALL_CASES"
}

if [[ "$LIST_CASES" -eq 1 ]]; then
  list_cases
  exit 0
fi

case "$CASE_IDS" in
  default|"") CASE_IDS="$DEFAULT_CASES" ;;
  all) CASE_IDS="$ALL_CASES" ;;
esac

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
  # Classic kscript / older kotlinc often break on JDK 25+; prefer JDK 21 when available.
  local -a env_args=(KSCRIPT_CACHE_DIR="$KS_CACHE")
  if [[ -n "${KSCRIPT_JAVA_HOME:-}" ]]; then
    env_args+=(JAVA_HOME="$KSCRIPT_JAVA_HOME" PATH="$KSCRIPT_JAVA_HOME/bin:$PATH")
  fi
  env "${env_args[@]}" "$KSCRIPT_BIN" "$@"
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

nudge_script() {
  # Tiny source edit so content-addressed caches miss and a recompile is required.
  printf '\n// bench-compare-nudge %s\n' "$(date +%s%N)" >>"$1"
}

append_result() {
  # Args via env: BENCH_ID LABEL SCRIPT ARGS_JSON DESC \
  #   K_COLD K_AFTER K_WARM KX_COLD KX_AFTER KX_WARM KX_DAEMON ERR
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
        "after_change": {
            "kscript_ms": num("BENCH_K_AFTER"),
            "kscriptx_ms": num("BENCH_KX_AFTER"),
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

# Mutable copies so "after small change" does not edit the repo tree.
EX_WORK="$WORK/examples"
cp -a "$ROOT/examples" "$EX_WORK"

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
  script="$WORK/$relpath"
  if [[ ! -f "$script" ]]; then
    log "skip $id: missing $script"
    python3 -c "import json; print(json.dumps({'id':'$id','error':'missing script'}))" >>"$RESULTS_JSONL"
    continue
  fi

  # shellcheck disable=SC2206
  script_args=($args)

  log "=== case $id ($label) ==="

  k_cold=""; k_after=""; k_warm=""
  kx_cold=""; kx_after=""; kx_warm=""; kx_daemon=""
  err=""

  # --- classic kscript ---
  if [[ "$SKIP_KSCRIPT" -eq 0 ]]; then
    # Fresh script copy per tool so kscript/kscriptx edits do not interact.
    rm -rf "$WORK/kscript-examples"
    cp -a "$EX_WORK" "$WORK/kscript-examples"
    ks_script="$WORK/kscript-examples/${relpath#examples/}"
    wipe_kscript_cache
    if k_cold="$(ms_run run_kscript "$ks_script" "${script_args[@]}")"; then
      nudge_script "$ks_script"
      if ! k_after="$(ms_run run_kscript "$ks_script" "${script_args[@]}")"; then
        err="${err}kscript after_change failed; "
        k_after=""
      fi
      if ! k_warm="$(median_n "$WARM_RUNS" run_kscript "$ks_script" "${script_args[@]}")"; then
        err="${err}kscript warm failed; "
        k_warm=""
      fi
    else
      err="${err}kscript cold failed; "
      k_cold=""
    fi
    log "  kscript cold=${k_cold:-na} after_change=${k_after:-na} warm=${k_warm:-na}"
  fi

  # --- kscriptx no-daemon ---
  export KSCRIPTX_DAEMON=0
  rm -rf "$WORK/kscriptx-examples"
  cp -a "$EX_WORK" "$WORK/kscriptx-examples"
  kx_script="$WORK/kscriptx-examples/${relpath#examples/}"
  wipe_kscriptx_cache
  if kx_cold="$(ms_run env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=0 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
      "$KSCRIPTX_BIN" --no-daemon "$kx_script" "${script_args[@]}")"; then
    nudge_script "$kx_script"
    if ! kx_after="$(ms_run env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=0 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
        "$KSCRIPTX_BIN" --no-daemon "$kx_script" "${script_args[@]}")"; then
      err="${err}kscriptx after_change failed; "
      kx_after=""
    fi
    if ! kx_warm="$(median_n "$WARM_RUNS" env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=0 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
        "$KSCRIPTX_BIN" --no-daemon "$kx_script" "${script_args[@]}")"; then
      err="${err}kscriptx warm failed; "
      kx_warm=""
    fi
  else
    err="${err}kscriptx cold failed; "
    kx_cold=""
  fi
  log "  kscriptx cold=${kx_cold:-na} after_change=${kx_after:-na} warm=${kx_warm:-na}"

  # --- kscriptx daemon warm (reuse cache from warm phase) ---
  if [[ "$SKIP_DAEMON" -eq 0 && -n "$kx_warm" ]]; then
    stop_daemon
    if start_daemon; then
      if env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=1 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
          "$KSCRIPTX_BIN" "$kx_script" "${script_args[@]}" >/dev/null 2>>"$LOG"; then
        if ! kx_daemon="$(median_n "$WARM_RUNS" env KSCRIPTX_DIRECTORY="$KX_HOME" KSCRIPTX_DAEMON=1 KSCRIPTX_NATIVE_KOTLINC="$NATIVE_ROOT" \
            "$KSCRIPTX_BIN" "$kx_script" "${script_args[@]}")"; then
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
    log "  kscriptx warm+daemon=${kx_daemon:-na}"
  fi

  export BENCH_ID="$id"
  export BENCH_LABEL="$label"
  export BENCH_SCRIPT="$relpath"
  BENCH_ARGS_JSON="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' ${script_args[@]+"${script_args[@]}"})"
  export BENCH_ARGS_JSON
  export BENCH_DESC="$desc"
  export BENCH_K_COLD="${k_cold:-}"
  export BENCH_K_AFTER="${k_after:-}"
  export BENCH_K_WARM="${k_warm:-}"
  export BENCH_KX_COLD="${kx_cold:-}"
  export BENCH_KX_AFTER="${kx_after:-}"
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
  "unit": "ms",
  "phases_help": {
    "cold": "First run, very cold: empty tool cache; resolve + compile + run. Shared ~/.m2 / Coursier repos may already be populated.",
    "after_change": "Second run after a tiny script edit (one sample). Must recompile; dependency jars from the cold run stay warm.",
    "warm": f"Unchanged script, cache hit, fresh JVM process each run (`kscriptx --no-daemon`). Median of {warm_runs} samples.",
    "warm_daemon": f"kscriptx only: same cache-hit script via an already-running background JVM (thin client over a local socket; median of {warm_runs} after one prime). Classic kscript has no daemon.",
  },
  "host": json.loads(Path(sys.argv[3]).read_text(encoding="utf-8")),
  "tools": json.loads(Path(sys.argv[4]).read_text(encoding="utf-8")),
  "results": rows,
}
out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
print(out)
PY

python3 - "$JSON_OUT" "$MD_OUT" "$ROOT/scripts/update-readme-bench.py" <<'PY'
import importlib.util, sys
from pathlib import Path

# Reuse README renderer for compare.md body (minus markers).
spec = importlib.util.spec_from_file_location("update_readme_bench", sys.argv[3])
mod = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(mod)
doc = __import__("json").loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
block = mod.render(doc)
# Strip HTML markers for the standalone markdown report.
body = block.replace(mod.START, "").replace(mod.END, "").strip()
Path(sys.argv[2]).write_text(
    "# kscript vs kscriptx benchmark\n\n" + body + "\n",
    encoding="utf-8",
)
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

# Fail the harness if any measured cell is missing (so README never ships holes).
python3 - "$JSON_OUT" "$SKIP_KSCRIPT" "$SKIP_DAEMON" <<'PY'
import json, sys
doc = json.loads(open(sys.argv[1], encoding="utf-8").read())
skip_ks = sys.argv[2] == "1"
skip_daemon = sys.argv[3] == "1"
bad = []
for r in doc.get("results") or []:
    rid = r.get("id")
    for phase in ("cold", "after_change", "warm"):
        p = (r.get("phases") or {}).get(phase) or {}
        if not skip_ks and p.get("kscript_ms") is None:
            bad.append(f"{rid}.{phase}.kscript")
        if p.get("kscriptx_ms") is None:
            bad.append(f"{rid}.{phase}.kscriptx")
    if not skip_daemon:
        d = (r.get("phases") or {}).get("warm_daemon") or {}
        if d.get("kscriptx_ms") is None:
            bad.append(f"{rid}.warm_daemon.kscriptx")
    if r.get("error"):
        bad.append(f"{rid}.error:{r['error']}")
if bad:
    print("Incomplete benchmark results:", file=sys.stderr)
    for b in bad:
        print(f"  - {b}", file=sys.stderr)
    sys.exit(1)
print("All timed cells present.")
PY

if [[ "${UPDATE_README:-1}" -eq 1 && "${SKIP_README:-0}" -eq 0 ]]; then
  python3 "$ROOT/scripts/update-readme-bench.py" "$JSON_OUT" "$ROOT/README.md"
fi

echo "Done. JSON=$JSON_OUT MD=$MD_OUT"
