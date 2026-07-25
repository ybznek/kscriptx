#!/usr/bin/env python3
"""Generate a Cursor canvas from bench-compare.json."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def default_json() -> Path:
    return repo_root() / "build" / "reports" / "perf-compare" / "compare.json"


def default_canvas() -> Path:
    env = os.environ.get("CANVAS_DIR")
    if env:
        return Path(env) / "kscript-vs-kscriptx.canvas.tsx"
    for c in (
        Path.home() / ".cursor" / "projects" / "p-kscript3" / "canvases",
        Path("/home/z/.cursor/projects/p-kscript3/canvases"),
    ):
        if c.is_dir():
            return c / "kscript-vs-kscriptx.canvas.tsx"
    return repo_root() / "build" / "reports" / "perf-compare" / "kscript-vs-kscriptx.canvas.tsx"


def ms(v: float | None) -> str:
    if v is None:
        return "fail"
    return str(int(round(v)))


def ratio(a: float | None, b: float | None) -> str:
    if a is None or b is None or b == 0:
        return "fail"
    return f"{a / b:.1f}×"


def j(s: object) -> str:
    return json.dumps(s, ensure_ascii=False)


def phase(r: dict, name: str) -> dict:
    return (r.get("phases") or {}).get(name) or {}


def num(v: float | None) -> float:
    return float(v) if v is not None else 0.0


def build_tsx(doc: dict) -> str:
    results = doc.get("results") or []
    if not results:
        raise SystemExit("compare.json has no results")

    tools = doc.get("tools") or {}
    ks = tools.get("kscript") or {}
    kx = tools.get("kscriptx") or {}
    host = doc.get("host") or {}
    phases_help = doc.get("phases_help") or {}

    case_ids: list[str] = []
    warm_ks: list[float] = []
    warm_kx: list[float] = []
    warm_daemon: list[float] = []
    cold_ks: list[float] = []
    cold_kx: list[float] = []
    after_ks: list[float] = []
    after_kx: list[float] = []
    warm_rows: list[list[str]] = []
    cold_rows: list[list[str]] = []
    after_rows: list[list[str]] = []
    desc_rows: list[list[str]] = []
    best_warm: float | None = None
    warm_kx_vals: list[float] = []
    failures: list[list[str]] = []

    for r in results:
        rid = r.get("id", "?")
        desc_rows.append([rid, r.get("label", rid), r.get("script", ""), r.get("description", "")])
        cold = phase(r, "cold")
        after = phase(r, "after_change")
        warm = phase(r, "warm")
        daemon = phase(r, "warm_daemon")
        ks_c, kx_c = cold.get("kscript_ms"), cold.get("kscriptx_ms")
        ks_a, kx_a = after.get("kscript_ms"), after.get("kscriptx_ms")
        ks_w, kx_w = warm.get("kscript_ms"), warm.get("kscriptx_ms")
        kx_d = daemon.get("kscriptx_ms")

        case_ids.append(rid)
        warm_ks.append(num(ks_w))
        warm_kx.append(num(kx_w))
        warm_daemon.append(num(kx_d))
        cold_ks.append(num(ks_c))
        cold_kx.append(num(kx_c))
        after_ks.append(num(ks_a))
        after_kx.append(num(kx_a))

        warm_rows.append(
            [rid, ms(ks_w), ms(kx_w), ms(kx_d), ratio(ks_w, kx_w), ratio(kx_w, kx_d)]
        )
        cold_rows.append([rid, ms(ks_c), ms(kx_c), ratio(ks_c, kx_c)])
        after_rows.append([rid, ms(ks_a), ms(kx_a), ratio(ks_a, kx_a)])

        if ks_w and kx_w:
            sp = ks_w / kx_w
            if best_warm is None or sp > best_warm:
                best_warm = sp
        if kx_w is not None:
            warm_kx_vals.append(float(kx_w))
        if r.get("error") or None in (ks_c, kx_c, ks_a, kx_a, ks_w, kx_w, kx_d):
            missing = []
            for label, val in (
                ("kscript cold", ks_c),
                ("kscriptx cold", kx_c),
                ("kscript after_change", ks_a),
                ("kscriptx after_change", kx_a),
                ("kscript warm", ks_w),
                ("kscriptx warm", kx_w),
                ("daemon", kx_d),
            ):
                if val is None:
                    missing.append(label)
            failures.append([rid, r.get("error") or ("missing: " + ", ".join(missing))])

    avg_warm = sum(warm_kx_vals) / len(warm_kx_vals) if warm_kx_vals else None
    footer = (
        f"Host: {host.get('os', '?')} · {host.get('java_version', '?')} · "
        f"kscript: {(ks.get('version') or 'n/a').strip()} · "
        f"kscriptx: {kx.get('version') or 'n/a'}"
    )

    phase_rows = [[k, v] for k, v in phases_help.items()] or [
        ["cold", "First run, very cold (empty tool cache)."],
        ["after_change", "Second run after a tiny script edit (recompile)."],
        ["warm", "Unchanged script, cache hit, new process."],
        ["warm_daemon", "Cache hit via kscriptx persistent JVM daemon."],
    ]

    fail_block = ""
    if failures:
        fail_block = f"""
      <Stack gap={{8}}>
        <H2>Failures</H2>
        <Table headers={{["Case", "Detail"]}} rows={{{j(failures)}}} />
      </Stack>
"""

    return f"""\
import {{
  BarChart,
  Callout,
  Card,
  CardBody,
  CardHeader,
  Divider,
  Grid,
  H1,
  H2,
  Stack,
  Stat,
  Table,
  Text,
}} from "cursor/canvas";

const GENERATED_AT = {j(doc.get("generated_at", ""))};
const WARM_RUNS = {j(str(doc.get("warm_runs", "?")))};
const CASE_IDS = {j(case_ids)};
const WARM_KSCRIPT = {j(warm_ks)};
const WARM_KSCRIPTX = {j(warm_kx)};
const WARM_DAEMON = {j(warm_daemon)};
const COLD_KSCRIPT = {j(cold_ks)};
const COLD_KSCRIPTX = {j(cold_kx)};
const AFTER_KSCRIPT = {j(after_ks)};
const AFTER_KSCRIPTX = {j(after_kx)};
const WARM_ROWS = {j(warm_rows)};
const COLD_ROWS = {j(cold_rows)};
const AFTER_ROWS = {j(after_rows)};
const CASE_ROWS = {j(desc_rows)};
const PHASE_ROWS = {j(phase_rows)};

export default function KscriptVsKscriptxBench() {{
  return (
    <Stack gap={{20}}>
      <Stack gap={{6}}>
        <H1>kscript vs kscriptx</H1>
        <Text tone="secondary">
          All times in milliseconds (ms). Warm columns = median of {{WARM_RUNS}} samples;
          cold and after-change are single runs. “Via daemon” = already-running background JVM.
          {{GENERATED_AT}}
        </Text>
      </Stack>

      <Grid columns={{4}} gap={{16}}>
        <Stat value={j(str(len(results)))} label="Cases" />
        <Stat value={j(f"{best_warm:.1f}×" if best_warm else "fail")} label="Best warm speedup" tone="success" />
        <Stat value={j(ms(avg_warm))} label="Avg warm kscriptx (ms)" />
        <Stat value={j("yes" if kx.get("native_kotlinc") else "no")} label="Native kotlinc" />
      </Grid>

      <Card>
        <CardHeader>Warm cache hit (ms)</CardHeader>
        <CardBody>
          <BarChart
            categories={{CASE_IDS}}
            series={{[
              {{ name: "kscript", data: WARM_KSCRIPT, tone: "neutral" }},
              {{ name: "kscriptx", data: WARM_KSCRIPTX, tone: "info" }},
              {{ name: "kscriptx via daemon", data: WARM_DAEMON, tone: "success" }},
            ]}}
            height={{280}}
            beginAtZero
          />
        </CardBody>
      </Card>

      <Stack gap={{8}}>
        <H2>Warm cache hit (ms)</H2>
        <Table
          headers={{["Case", "kscript (ms)", "kscriptx new process (ms)", "kscriptx via daemon (ms)", "kscript ÷ kscriptx", "kscriptx ÷ daemon"]}}
          columnAlign={{["left", "right", "right", "right", "right", "right"]}}
          rows={{WARM_ROWS}}
          striped
          stickyHeader
        />
        <Text tone="tertiary" size="small">
          New process = start a fresh JVM each run (--no-daemon). Via daemon = talk to an
          already-running background JVM (default). Ratio = left ÷ right (higher means faster right-hand path).
        </Text>
      </Stack>

      <Card>
        <CardHeader>Cold (1st run) vs after small change (ms)</CardHeader>
        <CardBody>
          <BarChart
            categories={{CASE_IDS}}
            series={{[
              {{ name: "kscript cold", data: COLD_KSCRIPT, tone: "neutral" }},
              {{ name: "kscriptx cold", data: COLD_KSCRIPTX, tone: "info" }},
              {{ name: "kscript after change", data: AFTER_KSCRIPT, tone: "warning" }},
              {{ name: "kscriptx after change", data: AFTER_KSCRIPTX, tone: "success" }},
            ]}}
            height={{280}}
            beginAtZero
          />
        </CardBody>
      </Card>

      <Stack gap={{8}}>
        <H2>Cold (1st run, empty cache) — ms</H2>
        <Table
          headers={{["Case", "kscript (ms)", "kscriptx (ms)", "kscript ÷ kscriptx"]}}
          columnAlign={{["left", "right", "right", "right"]}}
          rows={{COLD_ROWS}}
          striped
          stickyHeader
        />
      </Stack>

      <Stack gap={{8}}>
        <H2>After small change (2nd run, recompile) — ms</H2>
        <Table
          headers={{["Case", "kscript (ms)", "kscriptx (ms)", "kscript ÷ kscriptx"]}}
          columnAlign={{["left", "right", "right", "right"]}}
          rows={{AFTER_ROWS}}
          striped
          stickyHeader
        />
      </Stack>
{fail_block}
      <Stack gap={{8}}>
        <H2>Cases</H2>
        <Table headers={{["Id", "Label", "Script", "Description"]}} rows={{CASE_ROWS}} striped />
      </Stack>

      <Stack gap={{8}}>
        <H2>Phases</H2>
        <Table headers={{["Phase", "Meaning"]}} rows={{PHASE_ROWS}} />
      </Stack>

      <Callout tone="info" title="Re-run">
        ./scripts/bench-compare.sh --cases default
      </Callout>

      <Divider />
      <Text tone="tertiary" size="small">{j(footer)}</Text>
    </Stack>
  );
}}
"""


def main() -> None:
    json_path = Path(sys.argv[1]) if len(sys.argv) > 1 else default_json()
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else default_canvas()
    if not json_path.is_file():
        raise SystemExit(f"Missing {json_path}")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(build_tsx(json.loads(json_path.read_text(encoding="utf-8"))), encoding="utf-8")
    print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
