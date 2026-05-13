#!/usr/bin/env bash
set -euo pipefail

ERRORS=()

check_command() {
  local cmd="$1"
  local hint="$2"
  if ! command -v "$cmd" &>/dev/null; then
    ERRORS+=("MISSING: $cmd — $hint")
  fi
}

check_version() {
  local cmd="$1"
  local min_major="$2"
  local label="$3"
  if command -v "$cmd" &>/dev/null; then
    local ver
    ver=$("$cmd" --version 2>&1 | grep -oE '[0-9]+\.[0-9]+' | head -1)
    local major="${ver%%.*}"
    if [[ "$major" -lt "$min_major" ]]; then
      ERRORS+=("VERSION: $label requires >= $min_major (found $ver)")
    fi
  fi
}

# Required binaries
check_command "node"    "Install Node.js >= 20 from https://nodejs.org"
check_command "java"    "Install Java 21 from https://adoptium.net"
check_command "mvn"     "Install Maven 3.6+"
check_command "ffmpeg"  "Install ffmpeg: apt install ffmpeg  /  brew install ffmpeg"
check_command "ffprobe" "Install ffprobe (bundled with ffmpeg)"
check_command "rsync"   "Install rsync: apt install rsync  /  brew install rsync"
check_command "lsof"    "Install lsof (usually pre-installed)"

# Version checks
check_version "node" 20 "Node.js"
check_version "java" 21 "Java"

# TMDB_API_KEY must be set — either via .env.e2e or injected directly (e.g. Docker --env-file)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env.e2e"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi
if [[ -z "${TMDB_API_KEY:-}" ]]; then
  ERRORS+=("MISSING: TMDB_API_KEY — set it in e2e/.env.e2e or pass via environment")
fi

# Disk space: require at least 5 GB free in repo root
REPO_ROOT="$SCRIPT_DIR/../.."
FREE_KB=$(df -k "$REPO_ROOT" | awk 'NR==2 {print $4}')
MIN_KB=$((5 * 1024 * 1024))
if [[ "$FREE_KB" -lt "$MIN_KB" ]]; then
  ERRORS+=("DISK: less than 5 GB free at $REPO_ROOT (have $(( FREE_KB / 1024 / 1024 )) GB)")
fi

if [[ ${#ERRORS[@]} -gt 0 ]]; then
  echo "E2E preflight checks FAILED:" >&2
  for err in "${ERRORS[@]}"; do
    echo "  ✗ $err" >&2
  done
  exit 1
fi

echo "✓ All preflight checks passed."
