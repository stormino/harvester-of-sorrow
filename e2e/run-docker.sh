#!/usr/bin/env bash
# Convenience wrapper: pre-creates output directories so Docker doesn't
# create them as root, then runs the E2E test container.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RESULTS_DIR="${E2E_RESULTS_DIR:-$REPO_ROOT/e2e-results}"
mkdir -p "$RESULTS_DIR/target" "$RESULTS_DIR/report"

docker run --rm \
  --env-file "$SCRIPT_DIR/.env.e2e" \
  -v "$RESULTS_DIR/target:/app/target/e2e" \
  -v "$RESULTS_DIR/report:/app/e2e/test-results" \
  vixsrc-e2e "$@"
