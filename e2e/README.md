# E2E Tests

Playwright + Chromium suite that drives the real UI against the real backend.
No mocks — real TMDB, real vixsrc/RaiPlay, real ffmpeg, real rsync, real SQLite.

See [`../tasks/E2E_TESTING_STRATEGY.md`](../tasks/E2E_TESTING_STRATEGY.md) for the why and
[`../tasks/E2E_TESTING_IMPLEMENTATION_PLAN.md`](../tasks/E2E_TESTING_IMPLEMENTATION_PLAN.md) for the how.

## Quick start

```bash
npm install
npx playwright install chromium
cp .env.e2e.example .env.e2e   # fill in TMDB_API_KEY (and optionally RaiPlay creds)
npm test
```

## Helper structure

| File | Purpose |
|------|---------|
| `helpers/app-lifecycle.ts` | `globalSetup` / `globalTeardown` — boots and stops the Spring Boot app |
| `helpers/paths.ts` | Resolves absolute paths to download dirs, reads `.env.e2e` |
| `helpers/search.ts` | `searchAndPickFirst`, `openDownloadDialog`, `enqueueMovie`, `enqueueEpisode` |
| `helpers/queue.ts` | `gotoQueue`, `findLatestTaskId`, `waitForStatus`, `cancelTask` |
| `helpers/ffprobe.ts` | Runs `ffprobe` on a downloaded file, parses JSON |
| `helpers/assertions.ts` | `expectValidVideoFile` — custom matchers built on ffprobe |

## Adding a new scenario

1. Add an entry to `fixtures/content.json` if a new title is needed.
2. Create `tests/0N-description.spec.ts` following the shape of existing tests.
3. Tests run in file order (`fullyParallel: false`, `workers: 1`) — place slow tests late.

## Debugging a flaky test

- `npm run test:headed` — watches Chromium in real time.
- `npm run test:ui` — Playwright's interactive UI explorer.
- On failure, traces and screenshots land in `test-results/artifacts/`.
- Set `KEEP_E2E_ARTIFACTS=1` to preserve `target/e2e/` (app log, downloaded files, SQLite).
- App stdout is always at `target/e2e/app.stdout.log` (until teardown wipes it).

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `TMDB_API_KEY` | Yes | TMDB API key for search |
| `RAIPLAY_USERNAME` | No | RaiPlay email — RaiPlay tests skip if absent |
| `RAIPLAY_PASSWORD` | No | RaiPlay password |
| `KEEP_E2E_ARTIFACTS` | No | Set to `1` to preserve `target/e2e/` after the suite |
| `SERVER_PORT` | No | Default `8089` (avoids clash with dev server on 8080) |
