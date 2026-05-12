#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

PORT="${SERVER_PORT:-8089}"
PID_FILE="target/e2e/app.pid"

# Kill by process group (PGID = PID of the detached child) so Maven and any
# forked JVM children are all terminated together.
if [ -f "$PID_FILE" ]; then
  PGID=$(cat "$PID_FILE")
  if kill -0 "$PGID" 2>/dev/null; then
    echo "Sending SIGTERM to process group $PGID…"
    kill -TERM -"$PGID" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
fi

# Also kill anything still bound to the port (catches cases where exec changed
# the PID or an extra process slipped through).
STALE=$(lsof -ti :"$PORT" 2>/dev/null || true)
[ -n "$STALE" ] && kill -TERM $STALE 2>/dev/null || true

# Wait up to 10 s for graceful shutdown
for _ in {1..20}; do
  sleep 0.5
  lsof -ti :"$PORT" >/dev/null 2>&1 || exit 0
done

# Force-kill if still alive
STALE=$(lsof -ti :"$PORT" 2>/dev/null || true)
[ -n "$STALE" ] && kill -KILL $STALE 2>/dev/null || true
