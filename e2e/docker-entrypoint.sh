#!/usr/bin/env bash
set -e

# Bind-mount directories are created as root by Docker on the host.
# Chown them so the e2e user can create files inside them.
chown e2e:e2e /app/target/e2e /app/e2e/test-results 2>/dev/null || true

exec runuser -u e2e -- bash /app/e2e/run-tests.sh "$@"
