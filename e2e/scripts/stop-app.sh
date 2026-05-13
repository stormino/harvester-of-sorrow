#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

PORT="${SERVER_PORT:-8089}"
PID_FILE="target/e2e/app.pid"

# Kill the entire process group spawned by start-app.sh (PGID = PID of the
# detached child, stored in the PID file). This takes down Maven, the forked
# JVM, and the Vite dev-server child in one shot.
# We do NOT use "lsof -ti :PORT" here because that also matches Chromium
# connections from the test runner, which would kill the wrong processes.
if [ -f "$PID_FILE" ]; then
  PGID=$(cat "$PID_FILE")
  rm -f "$PID_FILE"
  if kill -0 "$PGID" 2>/dev/null; then
    echo "Sending SIGTERM to process group $PGID…"
    kill -TERM -"$PGID" 2>/dev/null || true

    # Wait up to 10 s for graceful shutdown
    for _ in {1..20}; do
      sleep 0.5
      kill -0 "$PGID" 2>/dev/null || exit 0
    done

    echo "Process group $PGID still alive — sending SIGKILL"
    kill -KILL -"$PGID" 2>/dev/null || true
  fi
else
  echo "No PID file found at $PID_FILE — app may have already exited"
fi
