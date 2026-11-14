# HOS UI

React 19 + TypeScript + Vite single-page app for Harvester of Sorrow. Built into the
backend jar (served by Spring Boot from `static/`), so there is one image and no CORS.

## Develop

```bash
# terminal 1: backend on :8080
mvn spring-boot:run
# terminal 2: UI on :5173, proxies /api to :8080 (override with HOS_BACKEND=http://host:port)
cd ui && npm ci && npm run dev
```

## Scripts

| Script | What it does |
|---|---|
| `npm run build` | Type-check and build into `dist/` (Maven packages it under `static/`) |
| `npm test` | Unit tests (live-update reducer, formatters, task helpers) |
| `npm run fetch:api` | Download `/v3/api-docs` from a running backend into `openapi.json` and regenerate `src/api/schema.d.ts` |
| `npm run gen:api` | Regenerate `src/api/schema.d.ts` from the committed `openapi.json` |

`.npmrc` sets `legacy-peer-deps=true`: npm 10's peer resolver crashes on vitest's optional peers.

## Layout

- `src/api` — generated OpenAPI types, fetch client (RFC 7807 errors), TanStack Query hooks
- `src/live` — the single SSE connection and its pure reducer (`snapshot` replaces, `task.*` upsert/delete)
- `src/components` — design-system components on the tokens in `src/styles/tokens`
- `src/shell` — navbar, sidebar, mobile drawer and tab bar
- `src/screens` — Search, Downloads, Library, Settings
