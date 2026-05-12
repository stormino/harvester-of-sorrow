#!/usr/bin/env bash
# Wrapper around 'npx playwright test' that guarantees stop-app.sh runs on
# any exit (normal, Ctrl+C, or SIGTERM), since Playwright does not reliably
# invoke globalTeardown when interrupted mid-test.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

cleanup() {
  bash "$SCRIPT_DIR/scripts/stop-app.sh" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

cd "$SCRIPT_DIR"
# Expose .env.e2e vars to the Playwright process (e.g. RAIPLAY_USERNAME for skip guards)
if [ -f "$SCRIPT_DIR/.env.e2e" ]; then
  set -a; source "$SCRIPT_DIR/.env.e2e"; set +a
fi
npx playwright test "$@"
