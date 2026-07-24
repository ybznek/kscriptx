#!/usr/bin/env bash
# Parse Kover/JaCoCo-style XML and print a markdown coverage summary.
# Usage: ./scripts/coverage-summary.sh path/to/report.xml
set -euo pipefail

XML="${1:?usage: coverage-summary.sh <kover-xml>}"
python3 - "$XML" <<'PY'
import sys, xml.etree.ElementTree as ET
from pathlib import Path

xml = Path(sys.argv[1])
if not xml.is_file():
    print(f"### Coverage\n\n_No report at `{xml}`._\n")
    sys.exit(0)

root = ET.parse(xml).getroot()

def counters(node):
    out = {}
    for c in node.findall("counter"):
        t = c.get("type")
        missed = int(c.get("missed", 0))
        covered = int(c.get("covered", 0))
        total = missed + covered
        pct = (100.0 * covered / total) if total else 0.0
        out[t] = (covered, missed, total, pct)
    return out

# Prefer report-level counters
totals = counters(root)
if not totals:
    # sum packages
    agg = {}
    for pkg in root.findall("package"):
        for t, v in counters(pkg).items():
            c, m, _, _ = v
            prev = agg.get(t, (0, 0))
            agg[t] = (prev[0] + c, prev[1] + m)
    totals = {}
    for t, (c, m) in agg.items():
        tot = c + m
        totals[t] = (c, m, tot, (100.0 * c / tot) if tot else 0.0)

print("### Coverage (JaCoCo)")
print()
print("| Counter | Covered | Missed | % |")
print("|---|---:|---:|---:|")
for key in ("LINE", "BRANCH", "INSTRUCTION", "METHOD", "CLASS"):
    if key in totals:
        c, m, _, pct = totals[key]
        print(f"| {key.title()} | {c} | {m} | {pct:.1f}% |")
print()
line = totals.get("LINE")
if line:
    print(f"**Line coverage: {line[3]:.1f}%**")
    print()
PY
