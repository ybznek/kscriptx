#!/usr/bin/env bash
# Parse Kover/JaCoCo-style XML and print a markdown coverage summary.
# Usage: ./scripts/coverage-summary.sh path/to/report.xml
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
XML="${1:?usage: coverage-summary.sh <kover-xml>}"

COV="$ROOT/bin/kscriptx-coverage"
if [[ ! -x "$COV" ]]; then
  COV="$ROOT/native/target/release/kscriptx-coverage"
fi
if [[ ! -x "$COV" ]]; then
  echo "kscriptx-coverage not found — build with: ./gradlew :cli:compileNativeHelpers" >&2
  exit 1
fi
exec "$COV" "$XML"
