#!/usr/bin/env bash
set -e

# Docker creates bind-mount directories as root on the host.
# Fix ownership before the e2e user tries to write to them.
chown -R e2e:e2e /app/target/e2e /app/e2e/test-results 2>/dev/null || true

exec runuser -u e2e -- bash /app/e2e/run-tests.sh "$@"
