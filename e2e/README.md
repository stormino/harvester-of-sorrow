# E2E Tests

Playwright + Chromium suite that drives the real UI against the real backend.
No mocks — real TMDB, real vixsrc/RaiPlay, real ffmpeg, real rsync, real SQLite.

**Expected runtime:** smoke test ~30 s · full suite ~10–20 min (4 real downloads at worst quality).

---

## Prerequisites

| Tool | Min version | Install |
|------|-------------|---------|
| Node.js | 20 | https://nodejs.org |
| Java | 21 | https://adoptium.net |
| Maven | 3.6 | https://maven.apache.org |
| ffmpeg + ffprobe | any recent | `apt install ffmpeg` / `brew install ffmpeg` |
| rsync | any | `apt install rsync` / `brew install rsync` |

A valid **TMDB API key** is also required (free at https://www.themoviedb.org/settings/api).

---

## First-time setup

```bash
cd e2e

# Install Node dependencies
npm install

# Download Playwright's Chromium browser
npx playwright install chromium

# Create your local env file and fill in the secrets
cp .env.e2e.example .env.e2e
```

Open `.env.e2e` and set at minimum:

```bash
TMDB_API_KEY=your_key_here
```

RaiPlay credentials are optional — tests 04 and 05 skip automatically if `RAIPLAY_USERNAME` is not set.

---

## Running the tests

All commands are run from the `e2e/` directory.

### Full suite

```bash
npm test
```

This automatically:
1. Runs the preflight check (validates all tools and `.env.e2e`)
2. Wipes `target/e2e/` from any previous run
3. Boots the Spring Boot app on port 8089 with a throwaway SQLite DB and download paths
4. Waits for the app to be healthy (up to 3 min — Vaadin frontend compile is slow on first boot)
5. Runs all 6 test files in order
6. Stops the app and leaves `target/e2e/` intact for inspection

To wipe `target/e2e/` after the run:

```bash
KEEP_E2E_ARTIFACTS=0 npm test
```

### Smoke test only (fast, no downloads)

```bash
bash run-tests.sh tests/01-smoke.spec.ts
```

Verifies the three main routes render. Completes in ~30 s.

### Single test file

```bash
bash run-tests.sh tests/02-movie-vixsrc.spec.ts
```

### Watch Chromium in real time

```bash
npm run test:headed
```

### Interactive Playwright UI explorer

```bash
npm run test:ui
```

### Check prerequisites without running tests

```bash
npm run preflight
```

---

## Running in Docker

Docker packages every dependency (Java 21, Maven, Node 20, ffmpeg, Chromium) so the tests run without any local setup beyond Docker itself.

### Build the image

Run from the **repository root**, passing your host UID/GID so volume files are owned by you:

```bash
docker build -f e2e/Dockerfile \
  --build-arg UID=$(id -u) --build-arg GID=$(id -g) \
  -t vixsrc-e2e .
```

The first build downloads Maven and npm dependencies — expect 5–10 min. Subsequent builds reuse cached layers.

### Run the full suite

Use the provided wrapper script — it pre-creates the output directories so Docker doesn't create them as root:

```bash
bash e2e/run-docker.sh
```

Results land in `e2e-results/` (relative to the repo root):
- `e2e-results/target/` — downloaded video files, app log (`app.stdout.log`), SQLite DB
- `e2e-results/report/html-report/` — Playwright HTML report (open `index.html` in a browser)

To use a different output location:

```bash
E2E_RESULTS_DIR=/tmp/my-results bash e2e/run-docker.sh
```

### Run a single test file

Pass the test path as an argument:

```bash
bash e2e/run-docker.sh tests/01-smoke.spec.ts
```

### RaiPlay tests

Set `RAIPLAY_USERNAME` and `RAIPLAY_PASSWORD` in your `.env.e2e` — they are already included in `.env.e2e.example`. No extra flags needed.

### Maven cache (optional speed-up)

Bind-mount your local Maven repository to avoid re-downloading dependencies on every run:

```bash
mkdir -p e2e-results/target e2e-results/report
docker run --rm \
  --env-file e2e/.env.e2e \
  -v "$HOME/.m2:/home/e2e/.m2" \
  -v "$(pwd)/e2e-results/target:/app/target/e2e" \
  -v "$(pwd)/e2e-results/report:/app/e2e/test-results" \
  vixsrc-e2e
```

### Viewing the HTML report

```bash
# After the run:
npx playwright show-report e2e-results/report/html-report
```

Or simply open `e2e-results/report/html-report/index.html` in your browser.

---

## What each test does

| File | Scenario | Downloads |
|------|----------|-----------|
| `01-smoke.spec.ts` | App boots; search, downloads, settings pages render | None |
| `02-movie-vixsrc.spec.ts` | Search "fight club", download movie, verify with ffprobe | ~1 vixsrc movie |
| `03-tv-vixsrc.spec.ts` | Search "rick and morty", download S01E01, verify path + ffprobe | ~1 vixsrc episode |
| `04-movie-raiplay.spec.ts` | Search "cosmonauta" on RaiPlay, download movie | ~1 RaiPlay movie |
| `05-tv-raiplay.spec.ts` | Search "rocco schiavone" on RaiPlay, download S01E01 | ~1 RaiPlay episode |
| `06-cancel.spec.ts` | Enqueue movie, cancel mid-download, assert no orphan processes or temp files | None (cancelled) |

Tests 04 and 05 skip automatically when `RAIPLAY_USERNAME` is not set.
Test 06 runs last intentionally — it cancels a download and must not affect earlier tests.

---

## Debugging failures

- **Traces and screenshots** are written to `test-results/artifacts/` on failure.
- **App stdout log** is at `target/e2e/app.stdout.log` while the suite is running.
- Set `KEEP_E2E_ARTIFACTS=1` to prevent teardown from wiping `target/e2e/` — lets you inspect the downloaded files and SQLite DB:

```bash
KEEP_E2E_ARTIFACTS=1 npm test
```

- For interactive debugging, `npm run test:ui` opens the Playwright trace viewer inline.

---

## Adding a new scenario

1. Add an entry to `fixtures/content.json` if a new title is needed.
2. Create `tests/0N-description.spec.ts` following the shape of an existing spec.
3. Tests run in filename order (`fullyParallel: false`, `workers: 1`) — place slow tests later in the sequence.

---

## Environment variables (`.env.e2e`)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TMDB_API_KEY` | **Yes** | — | TMDB API key for search |
| `RAIPLAY_USERNAME` | No | — | RaiPlay email; tests 04/05 skip if absent |
| `RAIPLAY_PASSWORD` | No | — | RaiPlay password |
| `DOWNLOAD_MOVIES_PATH` | No | `target/e2e/movies` | Where movies are saved |
| `DOWNLOAD_TV_SHOWS_PATH` | No | `target/e2e/tvshows` | Where TV episodes are saved |
| `DOWNLOAD_TEMP_PATH` | No | `target/e2e/temp` | Temp dir for in-progress downloads |
| `SQLITE_DB_PATH` | No | `target/e2e/vixsrc.db` | Separate DB for test runs |
| `SERVER_PORT` | No | `8089` | Avoids clash with a dev server on 8080 |
| `PARALLEL_DOWNLOADS` | No | `2` | Max concurrent downloads during tests |
| `DEFAULT_QUALITY` | No | `worst` | Keeps downloads small and fast |
| `DEFAULT_LANGUAGE` | No | `it` | Default audio language for downloads |
| `KEEP_E2E_ARTIFACTS` | No | keep | `target/e2e/` is kept by default; set to `0` to delete after the run |

---

## Helper structure

| File | Purpose |
|------|---------|
| `helpers/global-setup.ts` | Playwright `globalSetup` entry point — delegates to `app-lifecycle.ts` |
| `helpers/global-teardown.ts` | Playwright `globalTeardown` entry point — delegates to `app-lifecycle.ts` |
| `helpers/app-lifecycle.ts` | Preflight, app boot/wait/stop, `target/e2e/` lifecycle |
| `helpers/paths.ts` | Resolves absolute paths from `.env.e2e` |
| `helpers/search.ts` | `searchAndPickFirst`, `openDownloadDialog`, `enqueueMovie`, `enqueueEpisode` |
| `helpers/queue.ts` | `gotoQueue`, `findLatestTaskId`, `waitForStatus`, `cancelTask` |
| `helpers/ffprobe.ts` | Runs `ffprobe`, returns parsed stream + format info |
| `helpers/assertions.ts` | `expectValidVideoFile`, `findDownloadedFile` |
