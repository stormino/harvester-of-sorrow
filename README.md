# Harvester of Sorrow (HOS)

Self-hosted download manager for movies and TV shows from **VixSrc** (TMDB-indexed) and **RaiPlay**.
Spring Boot 3 (Java 21) backend with a React UI, SQLite, ffmpeg, live progress over Server-Sent Events.
One container, one port, no authentication (run it behind your reverse proxy).

> Upgrading from 3.x (VixsrcDownloader)? Read [docs/UPGRADING.md](docs/UPGRADING.md).

## Features

- Search VixSrc (via TMDB) and RaiPlay; results are filtered by real availability
- Queue a movie, a whole show, whole seasons or picked episodes (episode lists come from the source)
- Multiple audio languages and subtitles per download, quality selection
- Live queue: per-track progress, speed, ETA, cancel, retry, clear finished
- TV library: monitor show folders and auto-queue new episodes
- Responsive UI (phone: bottom tab bar, cards), dark and light themes
- REST API with OpenAPI docs at `/swagger-ui.html`

## Quick start (Docker)

```bash
git clone https://github.com/stormino/harvester-of-sorrow.git
cd harvester-of-sorrow
cp .env.example .env        # set TMDB_API_KEY
docker compose up -d --build
```

Open http://localhost:8080. Or use the published image `ghcr.io/stormino/harvester-of-sorrow:latest`
(uncomment `image:` in `docker-compose.yml`).

Keep `.env` comments on their own line: `docker --env-file` keeps inline `# …` as part of the value.

## Configuration

All settings are environment variables; the Settings screen shows them read-only. Change and restart.

| Variable | Default | Description |
|---|---|---|
| `TMDB_API_KEY` | (none) | Required for VixSrc search and metadata ([get one](https://www.themoviedb.org/settings/api)) |
| `DOWNLOAD_MOVIES_PATH` | `/downloads/movies` (image) | Movies destination |
| `DOWNLOAD_TV_SHOWS_PATH` | `/downloads/tvshows` (image) | TV destination; also the Library root |
| `DOWNLOAD_TEMP_PATH` | `/downloads/temp` (image) | Work area for segments and merging |
| `PARALLEL_DOWNLOADS` | `3` (compose) | Concurrent downloads |
| `SEGMENT_CONCURRENCY` | `5` | Concurrent HLS segment requests per track |
| `DEFAULT_QUALITY` | `best` | `best`, `1080`, `720`, `worst` |
| `DEFAULT_LANGUAGE` | `en` | Preselected audio language |
| `MONITORING_ENABLED` | `true` | Periodic check of monitored shows |
| `MONITORING_INTERVAL_MS` | `3600000` | Check interval |
| `RAIPLAY_USERNAME`, `RAIPLAY_PASSWORD` | (none) | Enable RaiPlay (both required) |
| `SQLITE_DB_PATH` | `data/hos.db` | Database file (falls back to `data/vixsrc.db` if only that exists) |
| `SERVER_PORT` | `8080` | HTTP port |
| `LOG_LEVEL` | `INFO` | Application log level |
| `LOGBOOK_LEVEL` | `INFO` | HTTP access log (`WARN` to silence) |
| `JAVA_OPTS` | `-Xmx512m -Xms256m` | JVM options (image) |

Volumes: `/downloads` (media) and `/app/data` (database). Healthcheck: `GET /actuator/health`.

### Reverse proxy

Live updates use SSE on `GET /api/v1/events`. Disable buffering for that path, e.g. nginx:

```nginx
location /api/v1/events {
    proxy_pass http://hos:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_read_timeout 1h;
}
```

The server already sends `X-Accel-Buffering: no` and a heartbeat every 15 s.

## API

- Swagger UI: `/swagger-ui.html`, spec: `/v3/api-docs`
- Everything under `/api/v1`; errors are RFC 7807 `application/problem+json`
- See [docs/REST_API_REFERENCE.md](docs/REST_API_REFERENCE.md)

## Development

Requirements: Java 21, Maven, Node 22, ffmpeg (and rsync) on the PATH.

```bash
mvn spring-boot:run                 # backend on :8080 (reads .env)
cd ui && npm ci && npm run dev      # UI on :5173, proxies /api to :8080
```

| Command | What it runs |
|---|---|
| `mvn test` | Unit tests |
| `mvn verify` | Unit + API integration tests (`*IT`) |
| `cd ui && npm test` | UI unit tests (live-update reducer, formatters) |
| `cd ui && npm run build && cd .. && mvn package` | Jar with the UI bundled under `static/` |
| `docker build -t hos .` | Full image (Node builds the UI, Maven the jar) |

After changing the API, regenerate the UI types with a running backend: `cd ui && npm run fetch:api`.

### Layout

```
src/main/java/.../api        REST controllers (v1), DTOs, SSE event stream
src/main/java/.../service    Download engine: queue, HLS, ffmpeg, sources (VixSrc, RaiPlay), monitoring
src/main/java/.../persistence SQLite repositories (Flyway migrations in resources/db/migration)
ui/                          React 19 + TypeScript + Vite single-page app
e2e/                         Playwright suite for the pre-5.0 Vaadin UI (see e2e/README.md)
```

## Files on disk

```
movies/   Title.Year.mp4
tvshows/  Show.Year/Season 01/Show - S01E01 - Episode.mp4
```

Monitoring only queues episodes newer than the latest `SxxEyy` already in the show folder.

## Troubleshooting

- **No search results:** check `TMDB_API_KEY` in Settings; VixSrc results are limited to titles VixSrc serves.
- **"Failed to extract playlist URL":** the source is unreachable or changed; check the container log.
- **Queue does not update live:** the SSE path is buffered by a proxy (see above); the navbar pill shows the connection state.
- **ffmpeg missing:** Settings → Check Tools.

## License

MIT
