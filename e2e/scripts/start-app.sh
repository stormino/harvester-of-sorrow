#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target/e2e
# Load e2e config into the environment
set -a; source e2e/.env.e2e; set +a
exec mvn -q spring-boot:run > target/e2e/app.stdout.log 2>&1
