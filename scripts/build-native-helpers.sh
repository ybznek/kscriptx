#!/usr/bin/env bash
# Build Rust helpers and install into bin/.
# Safe to run without cargo (exits 0 and skips).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/native"
export PATH="${HOME}/.cargo/bin:${PATH:-}"
mkdir -p target/release
if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo not found — skipping native helpers (daemon fast-path disabled)" >&2
  exit 0
fi
cargo build --release --quiet
install -m 0755 target/release/kscriptx-dclient "$ROOT/bin/kscriptx-dclient"
install -m 0755 target/release/kscriptx-coverage "$ROOT/bin/kscriptx-coverage"
