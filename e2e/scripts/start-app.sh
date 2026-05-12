#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target/e2e

# Load e2e config
set -a; source e2e/.env.e2e; set +a

# Pass every config value explicitly as Maven/JVM system properties so they
# reach Spring Boot regardless of whether spring-boot:run runs in-process or
# forked (env-var inheritance is unreliable in both modes under some Maven
# versions).
exec mvn spring-boot:run \
  -DSERVER_PORT="${SERVER_PORT:-8089}" \
  -DSQLITE_DB_PATH="${SQLITE_DB_PATH:-target/e2e/vixsrc.db}" \
  -DDOWNLOAD_MOVIES_PATH="${DOWNLOAD_MOVIES_PATH:-target/e2e/movies}" \
  -DDOWNLOAD_TV_SHOWS_PATH="${DOWNLOAD_TV_SHOWS_PATH:-target/e2e/tvshows}" \
  -DDOWNLOAD_TEMP_PATH="${DOWNLOAD_TEMP_PATH:-target/e2e/temp}" \
  -DLOG_FILE="${LOG_FILE:-target/e2e/app.log}" \
  -DPARALLEL_DOWNLOADS="${PARALLEL_DOWNLOADS:-2}" \
  -DDEFAULT_QUALITY="${DEFAULT_QUALITY:-worst}" \
  -DLOG_LEVEL="${LOG_LEVEL:-INFO}" \
  -DTMDB_API_KEY="${TMDB_API_KEY:-}" \
  -DRAIPLAY_USERNAME="${RAIPLAY_USERNAME:-}" \
  -DRAIPLAY_PASSWORD="${RAIPLAY_PASSWORD:-}" \
  > target/e2e/app.stdout.log 2>&1
