#!/bin/bash
# Build the UI and the jar, then run with the variables from .env.
set -euo pipefail
(cd ui && npm ci && npm run build)
mvn -B -DskipTests package
set -a
source .env
set +a
exec java ${JAVA_OPTS:-} -jar target/harvester-of-sorrow-*.jar
