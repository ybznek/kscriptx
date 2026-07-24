#!/usr/bin/env python3
"""Inject bench-compare results into README.md between markers.

Usage:
  ./scripts/update-readme-bench.py [compare.json] [README.md]

Markers (created if missing):
  <!-- BENCH-COMPARE:START -->
  ...
  <!-- BENCH-COMPARE:END -->
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

START = "<!-- BENCH-COMPARE:START -->"
END = "<!-- BENCH-COMPARE:END -->"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def fmt_ms(v: float | None) -> str:
    if v is None:
        return "—"
    if v >= 1000:
        return f"{v / 1000:.2f}s"
    return f"{v:.0f}"


def speedup(a: float | None, b: float | None) -> str:
    if a is None or b is None or b == 0:
        return "—"
    return f"{a / b:.1f}×"


def render(doc: dict) -> str:
    lines: list[str] = []
    lines.append(START)
    lines.append("")
    lines.append(
        f"_Auto-generated from `scripts/bench-compare.sh` · "
        f"{doc.get('generated_at', '')} · warm median of "
        f"**{doc.get('warm_runs', '?')}** samples._"
    )
    lines.append("")
    lines.append("| Case | Phase | kscript (ms) | kscriptx (ms) | kscriptx daemon (ms) | Speedup |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for r in doc.get("results") or []:
        rid = r.get("id", "?")
        phases = r.get("phases") or {}
        for phase, label in (("cold", "cold"), ("warm", "warm"), ("warm_daemon", "warm+daemon")):
            p = phases.get(phase) or {}
            ks = p.get("kscript_ms")
            kx = p.get("kscriptx_ms")
            if phase == "warm_daemon":
                lines.append(
                    f"| `{rid}` | {label} | — | — | {fmt_ms(kx)} | — |"
                )
            else:
                lines.append(
                    f"| `{rid}` | {label} | {fmt_ms(ks)} | {fmt_ms(kx)} | — | {speedup(ks, kx)} |"
                )
        if r.get("error"):
            lines.append(f"| `{rid}` | notes | | | | {r['error']} |")

    lines.append("")
    tools = doc.get("tools") or {}
    ks = tools.get("kscript") or {}
    kx = tools.get("kscriptx") or {}
    host = doc.get("host") or {}
    lines.append(
        f"Host: `{host.get('os', '?')}` · `{host.get('java_version', '?')}`  "
    )
    lines.append(
        f"Tools: kscript `{ks.get('version') or 'n/a'}` · "
        f"kscriptx `{kx.get('version') or 'n/a'}` · "
        f"native kotlinc={'yes' if kx.get('native_kotlinc') else 'no'}"
    )
    lines.append("")
    lines.append(
        "Re-run: `./scripts/bench-compare.sh` "
        "(also refreshes the Cursor canvas and this section via "
        "`scripts/gen-bench-canvas.py` + `scripts/update-readme-bench.py`)."
    )
    lines.append("")
    lines.append(END)
    return "\n".join(lines)


def inject(readme: Path, block: str) -> None:
    text = readme.read_text(encoding="utf-8")
    if START in text and END in text:
        before, rest = text.split(START, 1)
        _, after = rest.split(END, 1)
        text = before.rstrip() + "\n\n" + block + "\n" + after.lstrip("\n")
    else:
        # Insert before ## License if present, else append.
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
    doc = json.loads(json_path.read_text(encoding="utf-8"))
    inject(readme, render(doc))
    print(f"Updated {readme}")


if __name__ == "__main__":
    main()
