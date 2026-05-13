#!/usr/bin/env bash
set -e

# Bind-mount directories (and their parent /app/target) may be owned by root
# on the host. Chown them so the e2e user can write to them.
chown e2e:e2e /app/target /app/target/e2e /app/e2e/test-results 2>/dev/null || true

exec runuser -u e2e -- bash /app/e2e/run-tests.sh "$@"
