#!/usr/bin/env python3
"""Inject bench-compare results into README.md between markers.

Readable phase tables; all times in integer milliseconds.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

START = "<!-- BENCH-COMPARE:START -->"
END = "<!-- BENCH-COMPARE:END -->"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def ms(v: float | None) -> str:
    if v is None:
        return "fail"
    return str(int(round(v)))


def ratio(a: float | None, b: float | None) -> str:
    if a is None or b is None or b == 0:
        return "fail"
    return f"{a / b:.1f}×"


def phase(r: dict, name: str) -> dict:
    return (r.get("phases") or {}).get(name) or {}


def render(doc: dict) -> str:
    results = doc.get("results") or []
    tools = doc.get("tools") or {}
    ks = tools.get("kscript") or {}
    kx = tools.get("kscriptx") or {}
    host = doc.get("host") or {}
    warm_runs = doc.get("warm_runs", "?")

    lines: list[str] = [
        START,
        "",
        f"_Generated `{doc.get('generated_at', '')}` · all times in **milliseconds (ms)** · "
        f"warm columns = median of **{warm_runs}** samples; cold & after-change = single run._",
        "",
        "| Phase | Meaning |",
        "|---|---|",
        "| **Cold (1st run)** | Empty tool cache — first resolve + compile + run (very cold). |",
        "| **After small change** | Second run after a tiny script edit — must recompile; dependency jars may already be warm. |",
        "| **Warm (new process)** | Unchanged script, cache hit. Each sample starts a **fresh JVM process**, then exits. |",
        "| **Warm (via daemon)** | Same cache-hit script, but kscriptx talks to an **already-running background JVM** "
        "(started once; stays hot). No JVM startup per run. Classic kscript has no equivalent. |",
        "",
        "Ratio columns show **how many times faster** the right-hand tool is "
        "(e.g. `4.0×` means about 4× faster). Formula is in the column header.",
        "",
        "### Cold (1st run, empty cache) — ms",
        "",
        "| Case | kscript (ms) | kscriptx (ms) | kscript ÷ kscriptx |",
        "|---|---:|---:|---:|",
    ]

    notes: list[str] = []
    for r in results:
        rid = r.get("id", "?")
        cold = phase(r, "cold")
        ks_c = cold.get("kscript_ms")
        kx_c = cold.get("kscriptx_ms")
        lines.append(f"| `{rid}` | {ms(ks_c)} | {ms(kx_c)} | {ratio(ks_c, kx_c)} |")
        if r.get("error"):
            notes.append(f"- `{rid}`: {r['error']}")

    lines += [
        "",
        "### After small change (2nd run, recompile) — ms",
        "",
        "| Case | kscript (ms) | kscriptx (ms) | kscript ÷ kscriptx |",
        "|---|---:|---:|---:|",
    ]
    for r in results:
        rid = r.get("id", "?")
        after = phase(r, "after_change")
        ks_a = after.get("kscript_ms")
        kx_a = after.get("kscriptx_ms")
        lines.append(f"| `{rid}` | {ms(ks_a)} | {ms(kx_a)} | {ratio(ks_a, kx_a)} |")

    lines += [
        "",
        "### Warm cache hit (unchanged script) — ms",
        "",
        "| Case | kscript (ms) | kscriptx new process (ms) | kscriptx via daemon (ms) | kscript ÷ kscriptx | kscriptx ÷ daemon |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for r in results:
        rid = r.get("id", "?")
        warm = phase(r, "warm")
        daemon = phase(r, "warm_daemon")
        ks_w = warm.get("kscript_ms")
        kx_w = warm.get("kscriptx_ms")
        kx_d = daemon.get("kscriptx_ms")
        lines.append(
            f"| `{rid}` | {ms(ks_w)} | {ms(kx_w)} | {ms(kx_d)} | "
            f"{ratio(ks_w, kx_w)} | {ratio(kx_w, kx_d)} |"
        )

    if notes:
        lines += ["", "**Failures**", ""] + notes

    lines += [
        "",
        f"Host: `{host.get('os', '?')}` · `{host.get('java_version', '?')}`  ",
        f"Tools: kscript `{(ks.get('version') or 'n/a').strip()}` · "
        f"kscriptx `{kx.get('version') or 'n/a'}` · "
        f"native kotlinc={'yes' if kx.get('native_kotlinc') else 'no'}",
        "",
        "- **kscriptx new process** — `kscriptx --no-daemon`: start JVM, run script, exit (every sample).  ",
        "- **kscriptx via daemon** — background `kscriptx` JVM already running; each sample is a client "
        "request over a local socket (default mode). Classic kscript has no daemon column.  ",
        "- **kscript ÷ kscriptx** — e.g. `4.0×` means kscriptx (new process) finished in ~¼ the time of kscript.  ",
        "- **kscriptx ÷ daemon** — e.g. `12×` means the daemon path was ~12× faster than starting a new "
        "kscriptx JVM for the same warm script.  ",
        "Re-run: `./scripts/bench-compare.sh`.",
        "",
        END,
    ]
    return "\n".join(lines)


def inject(readme: Path, block: str) -> None:
    text = readme.read_text(encoding="utf-8")
    if START in text and END in text:
        before, rest = text.split(START, 1)
        _, after = rest.split(END, 1)
        text = before.rstrip() + "\n\n" + block + "\n" + after.lstrip("\n")
    else:
        needle = "## License"
        if needle in text:
            pre, post = text.split(needle, 1)
            text = pre.rstrip() + "\n\n## Benchmark results\n\n" + block + "\n\n" + needle + post
        else:
            text = text.rstrip() + "\n\n## Benchmark results\n\n" + block + "\n"
    readme.write_text(text, encoding="utf-8")


def main() -> None:
    root = repo_root()
    json_path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build/reports/perf-compare/compare.json"
    readme = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "README.md"
    if not json_path.is_file():
        raise SystemExit(f"Missing {json_path}. Run ./scripts/bench-compare.sh first.")
    inject(readme, render(json.loads(json_path.read_text(encoding="utf-8"))))
    print(f"Updated {readme}")


if __name__ == "__main__":
    main()
