#!/usr/bin/env bash
# Build Rust helpers and install into bin/.
# Safe to skip when cargo is missing or when not on a native Linux/macOS host
# (Windows CI often has a broken cargo→WSL stub).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/native"
export PATH="${HOME}/.cargo/bin:${PATH:-}"
mkdir -p target/release

# Git-Bash / MSYS / CYGWIN: skip (Windows package does not require dclient).
uname_s="$(uname -s 2>/dev/null || true)"
case "$uname_s" in
  MINGW*|MSYS*|CYGWIN*)
    echo "skipping native helpers on $uname_s (Windows portable uses JVM path)" >&2
    exit 0
    ;;
esac

if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo not found — skipping native helpers (daemon fast-path disabled)" >&2
  exit 0
fi
cargo build --release --quiet
install -m 0755 target/release/kscriptx-dclient "$ROOT/bin/kscriptx-dclient"
install -m 0755 target/release/kscriptx-coverage "$ROOT/bin/kscriptx-coverage"
