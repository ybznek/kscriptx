#!/usr/bin/env bash
# Resolve the newest GraalVM with native-image and export JAVA_HOME + PATH.
#
# Preference order:
#   1. GRAALVM_HOME if it already has native-image
#   2. Ensure latest SDKMAN GraalVM CE (ENSURE_LATEST_GRAAL=1, default), then pick newest installed
#   3. native-image already on PATH
#
# Env:
#   ENSURE_LATEST_GRAAL=1  — install newest published *-graalce via SDKMAN when needed (default)
#   GRAALVM_HOME           — force a specific install
#
# Usage (source from other scripts):
#   source "$ROOT/scripts/resolve-graalvm.sh"
#   resolve_graalvm || exit 1
#
# Or run directly:
#   ./scripts/resolve-graalvm.sh   # prints JAVA_HOME and version

_ksx_sdkman_java_root() {
  echo "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"
}

# Extract a sortable version key from an SDKMAN identifier (e.g. 25.0.2-graalce → 25.0.2).
_ksx_ver_key() {
  local id="$1"
  local base
  base="$(basename "$id")"
  base="${base%-graalce}"
  base="${base%-graal}"
  printf '%s' "$base"
}

# Print "version<TAB>path" lines for installed GraalVM JDKs.
_ksx_list_installed_graal() {
  local root="$1"
  local d
  shopt -s nullglob
  for d in "$root"/*-graalce "$root"/*-graal; do
    [[ -d "$d" ]] || continue
    [[ -x "$d/bin/native-image" ]] || continue
    printf '%s\t%s\n' "$(_ksx_ver_key "$d")" "$d"
  done
  shopt -u nullglob
}

_ksx_newest_installed_graal() {
  local root
  root="$(_ksx_sdkman_java_root)"
  [[ -d "$root" ]] || return 1
  _ksx_list_installed_graal "$root" | sort -t$'\t' -k1,1V | tail -1 | cut -f2-
}

# Newest published GraalVM CE identifier from `sdk list java` (e.g. 25.0.2-graalce).
_ksx_latest_published_graalce() {
  # shellcheck disable=SC1090
  source "${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh" >/dev/null 2>&1 || return 1
  sdk list java 2>/dev/null \
    | grep -E '[[:space:]]graalce[[:space:]]' \
    | grep -oE '[0-9]+(\.[0-9]+){1,2}-graalce' \
    | sort -t- -k1,1V \
    | tail -1
}

_ksx_version_gt() {
  # true if $1 > $2 (sort -V)
  local a="$1" b="$2"
  [[ "$a" != "$b" && "$(printf '%s\n%s\n' "$a" "$b" | sort -V | tail -1)" == "$a" ]]
}

_ksx_ensure_latest_graalce() {
  local ensure="${ENSURE_LATEST_GRAAL:-1}"
  case "$ensure" in
    0|false|FALSE|no|NO|off|OFF) return 0 ;;
  esac

  local sdk_init="${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh"
  [[ -f "$sdk_init" ]] || return 0

  # shellcheck disable=SC1090
  source "$sdk_init" >/dev/null 2>&1 || return 0

  local latest root newest_path installed_key latest_key
  latest="$(_ksx_latest_published_graalce || true)"
  [[ -n "${latest:-}" ]] || return 0

  root="$(_ksx_sdkman_java_root)"
  if [[ -x "$root/$latest/bin/native-image" ]]; then
    return 0
  fi

  newest_path="$(_ksx_newest_installed_graal || true)"
  if [[ -n "${newest_path:-}" ]]; then
    installed_key="$(_ksx_ver_key "$newest_path")"
    latest_key="$(_ksx_ver_key "$latest")"
    if ! _ksx_version_gt "$latest_key" "$installed_key"; then
      return 0
    fi
  fi

  echo "==> installing latest GraalVM CE via SDKMAN: $latest" >&2
  # Non-interactive; tolerate "already installed".
  if ! sdk install java "$latest" </dev/null; then
    echo "warning: sdk install java $latest failed; using newest already installed" >&2
  fi
}

resolve_graalvm() {
  if [[ -n "${GRAALVM_HOME:-}" && -x "${GRAALVM_HOME}/bin/native-image" ]]; then
    export JAVA_HOME="$GRAALVM_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
    hash -r 2>/dev/null || true
    echo "==> GraalVM: $JAVA_HOME (GRAALVM_HOME)" >&2
    native-image --version 2>&1 | head -1 >&2 || true
    return 0
  fi

  _ksx_ensure_latest_graalce || true

  local newest
  newest="$(_ksx_newest_installed_graal || true)"
  if [[ -n "${newest:-}" && -x "$newest/bin/native-image" ]]; then
    export JAVA_HOME="$newest"
    export PATH="$JAVA_HOME/bin:$PATH"
    hash -r 2>/dev/null || true
    echo "==> GraalVM: $JAVA_HOME (newest SDKMAN)" >&2
    native-image --version 2>&1 | head -1 >&2 || true
    return 0
  fi

  if command -v native-image >/dev/null 2>&1; then
    local ni
    ni="$(command -v native-image)"
    export JAVA_HOME="$(cd "$(dirname "$ni")/.." && pwd)"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "==> GraalVM: $JAVA_HOME (PATH)" >&2
    native-image --version 2>&1 | head -1 >&2 || true
    return 0
  fi

  echo "error: native-image not found. Install the latest GraalVM CE, e.g.:" >&2
  echo "  sdk install java 25.0.2-graalce" >&2
  echo "  # or set GRAALVM_HOME=..." >&2
  return 1
}

if [[ "${BASH_SOURCE[0]:-}" == "${0:-}" ]]; then
  set -euo pipefail
  resolve_graalvm
  echo "JAVA_HOME=$JAVA_HOME"
fi
