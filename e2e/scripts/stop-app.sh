#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

PID=$(lsof -ti:8089 || true)
if [[ -n "$PID" ]]; then
  kill -TERM "$PID" || true
fi

# Wait up to 10 s for graceful shutdown
for _ in {1..20}; do
  sleep 0.5
  lsof -ti:8089 >/dev/null 2>&1 || exit 0
done

# Force-kill if still alive
PID=$(lsof -ti:8089 || true)
[[ -n "$PID" ]] && kill -KILL "$PID" || true
