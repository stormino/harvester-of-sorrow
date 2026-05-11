# E2E Testing — Implementation Plan

> Companion to [E2E_TESTING_STRATEGY.md](E2E_TESTING_STRATEGY.md). That doc explains *why* and *what*; this doc is the executable *how*.
>
> Scope reminder: **UI only**, **no mocks**, **local-only**, Playwright + Chromium, `worst` quality, 4 real downloads per run, ~10–20 min runtime. Output verification via **ffprobe**.

---

## 0. Prerequisites

Before any code is written, confirm the developer machine has:

- Node.js ≥ 20 (for Playwright runtime)
- Java 21 + Maven (existing requirement)
- `ffmpeg` and `ffprobe` on `PATH` (`which ffmpeg ffprobe`)
- `rsync` on `PATH`
- A valid `TMDB_API_KEY`
- Valid `RAIPLAY_USERNAME` + `RAIPLAY_PASSWORD` (RaiPlay scenarios need login; otherwise RaiPlay results are filtered out — see [application.yml](src/main/resources/application.yml) lines 24–30)
- ≥ 5 GB free disk space in the repo

A `e2e/scripts/preflight.sh` script will assert all of the above and fail fast with a clear message if any is missing.

---

## 1. Directory layout

Top-level `e2e/`, gitignored where appropriate:

```
e2e/
├── package.json
├── playwright.config.ts
├── tsconfig.json
├── .env.e2e.example         # template; real .env.e2e is gitignored
├── .gitignore
├── scripts/
│   ├── preflight.sh         # checks ffprobe/rsync/node/java
│   ├── start-app.sh         # boots the app with E2E config
│   └── stop-app.sh          # stops the app, cleans target/e2e
├── fixtures/
│   └── content.json         # the 4 titles
├── helpers/
│   ├── app-lifecycle.ts     # boot/wait-for-ready/stop
│   ├── search.ts            # search & enqueue actions
│   ├── queue.ts             # poll queue for status, get download path
│   ├── ffprobe.ts           # run ffprobe, parse JSON
│   ├── assertions.ts        # custom expect matchers
│   └── paths.ts             # resolve where files land
├── tests/
│   ├── 01-smoke.spec.ts
│   ├── 02-movie-vixsrc.spec.ts
│   ├── 03-tv-vixsrc.spec.ts
│   ├── 04-movie-raiplay.spec.ts
│   ├── 05-tv-raiplay.spec.ts
│   └── 06-cancel.spec.ts
└── test-results/            # gitignored: traces, screenshots, app logs
```

Root `.gitignore` additions:
```
e2e/node_modules/
e2e/test-results/
e2e/.env.e2e
target/e2e/
```

---

## 2. Application boot configuration

A dedicated set of env vars points the running app at a clean, throwaway location. The app code is unchanged — only configuration differs.

`e2e/.env.e2e.example` (committed):
```bash
# Copy to .env.e2e and fill in secrets
TMDB_API_KEY=
RAIPLAY_USERNAME=
RAIPLAY_PASSWORD=

# Test paths — relative to repo root
DOWNLOAD_MOVIES_PATH=target/e2e/movies
DOWNLOAD_TV_SHOWS_PATH=target/e2e/tvshows
DOWNLOAD_TEMP_PATH=target/e2e/temp
SQLITE_DB_PATH=target/e2e/vixsrc.db
LOG_FILE=target/e2e/app.log

# Test tuning
PARALLEL_DOWNLOADS=2
DEFAULT_QUALITY=worst
SERVER_PORT=8089
LOG_LEVEL=INFO
```

Port `8089` avoids collision with a developer's local `mvn spring-boot:run` on `8080`.

`e2e/scripts/start-app.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target/e2e
set -a; source e2e/.env.e2e; set +a
exec mvn -q spring-boot:run > target/e2e/app.stdout.log 2>&1
```

`e2e/scripts/stop-app.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Kill anything bound to the test port
PID=$(lsof -ti:8089 || true)
[ -n "$PID" ] && kill -TERM "$PID" || true
# Wait up to 10s for graceful shutdown
for _ in {1..20}; do
  sleep 0.5
  lsof -ti:8089 >/dev/null 2>&1 || exit 0
done
PID=$(lsof -ti:8089 || true)
[ -n "$PID" ] && kill -KILL "$PID" || true
```

The Playwright `globalSetup` will:
1. Run `preflight.sh` (fails the suite early on missing deps).
2. Wipe `target/e2e/` from any previous run.
3. Spawn `start-app.sh` as a detached child process.
4. Poll `GET http://localhost:8089/actuator/health` until `{"status":"UP"}` (timeout 90 s — Vaadin frontend compile can be slow on first run).
5. Store the app PID in `target/e2e/app.pid`.

`globalTeardown` runs `stop-app.sh` and optionally preserves `target/e2e/` on failure (controlled by `KEEP_E2E_ARTIFACTS=1`).

---

## 3. Playwright scaffold

`e2e/package.json`:
```json
{
  "name": "vixsrc-downloader-e2e",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "playwright test",
    "test:headed": "playwright test --headed",
    "test:ui": "playwright test --ui",
    "preflight": "./scripts/preflight.sh"
  },
  "devDependencies": {
    "@playwright/test": "^1.50.0",
    "@types/node": "^22.0.0",
    "typescript": "^5.4.0"
  }
}
```

`e2e/playwright.config.ts` — key points:
- `testDir: './tests'`
- `globalSetup: './helpers/app-lifecycle.ts'` (exports `globalSetup`)
- `globalTeardown: './helpers/app-lifecycle.ts'` (exports `globalTeardown`)
- `fullyParallel: false` — tests share a single booted app and a single SQLite DB; running them in parallel would race on the queue.
- `workers: 1`
- `retries: 0` locally, allow `retries: 1` if `CI=1`
- `timeout: 25 * 60 * 1000` per test (download takes real minutes)
- `use.baseURL: 'http://localhost:8089'`
- `use.trace: 'retain-on-failure'`
- `use.video: 'retain-on-failure'`
- `use.screenshot: 'only-on-failure'`
- `projects: [{ name: 'chromium', use: devices['Desktop Chrome'] }]`

`e2e/tsconfig.json` — standard strict TS targeting ES2022.

---

## 4. `data-testid` additions to view code

Minimal, surgical changes. The convention: `setId(String)` on Vaadin components renders as `id="..."` on the host element — perfect for Playwright's `#search-input` style selectors. Where ids conflict with Vaadin's own usage, fall back to `getElement().setAttribute("data-testid", ...)`.

### [SearchView.java](src/main/java/com/github/stormino/view/SearchView.java)
| Element | Attribute |
|---------|-----------|
| `searchField` | `setId("search-input")` |
| `searchButton` | `setId("search-button")` |
| `contentTypeGroup` | `setId("content-type-filter")` |
| `resultsContainer` | `setId("search-results")` |

### [SearchResultCard.java](src/main/java/com/github/stormino/view/component/SearchResultCard.java)
| Element | Attribute |
|---------|-----------|
| Card root | `getElement().setAttribute("data-testid", "result-card")` and `data-source` (`vixsrc`/`raiplay`), `data-type` (`MOVIE`/`TV`), `data-title` |
| Card download button (line ~185) | `setId("result-card-download-" + content.getTmdbId())` |
| Dialog season field | `setId("dialog-season-field")` |
| Dialog episode field | `setId("dialog-episode-field")` |
| Dialog language selector | `setId("dialog-language-selector")` |
| Dialog quality selector | `setId("dialog-quality-selector")` |
| Dialog "Add to Queue" button | `setId("dialog-confirm-download")` |

### [DownloadQueueView.java](src/main/java/com/github/stormino/view/DownloadQueueView.java)
| Element | Attribute |
|---------|-----------|
| `treeGrid` | `setId("queue-grid")` |
| `clearCompletedBtn` | `setId("clear-completed-button")` |
| Status badge (line ~679) | `badge.getElement().setAttribute("data-testid", "queue-row-status")` and `data-task-id`, `data-status` |
| Cancel button (line ~759) | `cancelBtn.getElement().setAttribute("data-testid", "queue-row-cancel")` and `data-task-id` |

`data-task-id` on row-level elements is the key that test helpers use to poll a specific task without mis-matching rows when multiple tasks coexist.

### [SettingsView.java](src/main/java/com/github/stormino/view/SettingsView.java)
- Root `setId("settings-view")` — enough for the smoke test to verify the view loaded.

---

## 5. Helpers

### `helpers/paths.ts`
Computes absolute paths from the repo root for `target/e2e/movies`, `target/e2e/tvshows`, etc. Reads `.env.e2e` to stay in sync with the app's config.

### `helpers/app-lifecycle.ts`
Exports `globalSetup` and `globalTeardown` as described in §2. Uses `node:child_process` `spawn` (detached, `stdio: 'ignore'`), writes PID file, polls `/actuator/health`.

### `helpers/search.ts`
```ts
type Source = 'vixsrc' | 'raiplay';
type ContentType = 'MOVIE' | 'TV';

searchAndPickFirst(page, query: string, source: Source, type: ContentType): Promise<Locator>
// Fills #search-input, clicks #search-button, waits for #search-results
// to contain at least one [data-testid=result-card][data-source=$source][data-type=$type],
// returns the first matching card locator.

openDownloadDialog(card: Locator): Promise<void>
// Clicks the card's download button (#result-card-download-...).

enqueueMovie(page, opts: { quality?: 'worst' }): Promise<void>
// In the open dialog: selects quality, clicks #dialog-confirm-download.

enqueueEpisode(page, opts: { season: number; episode: number; quality?: 'worst' }): Promise<void>
// Fills season/episode fields in the dialog, clicks #dialog-confirm-download.
```

### `helpers/queue.ts`
```ts
gotoQueue(page): Promise<void>  // navigates to /downloads

// Polls the queue view until a single task exists with the given source+type, returns its taskId.
// Used right after enqueue when we don't yet know the taskId.
findLatestTaskId(page, opts: { source: Source }): Promise<string>

// Waits until the row for taskId reaches one of the target statuses.
// Uses [data-testid=queue-row-status][data-task-id=$taskId][data-status=$status].
waitForStatus(
  page,
  taskId: string,
  targetStatuses: DownloadStatus[],
  opts?: { timeoutMs?: number }
): Promise<DownloadStatus>

cancelTask(page, taskId: string): Promise<void>
// Clicks [data-testid=queue-row-cancel][data-task-id=$taskId].
```

`DownloadStatus` is a TypeScript union matching the Java enum: `'QUEUED' | 'EXTRACTING' | 'DOWNLOADING' | 'MERGING' | 'COPYING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'NOT_FOUND'`.

### `helpers/ffprobe.ts`
```ts
type StreamInfo = {
  codec_type: 'video' | 'audio' | 'subtitle';
  codec_name: string;
  width?: number;
  height?: number;
  channels?: number;
  duration?: string;
  tags?: { language?: string; title?: string };
};

type ProbeResult = {
  format: { duration: string; size: string; bit_rate: string };
  streams: StreamInfo[];
};

probe(filePath: string): Promise<ProbeResult>
// Runs: ffprobe -v error -print_format json -show_streams -show_format <file>
// Parses JSON, throws on non-zero exit.
```

### `helpers/assertions.ts`
Custom matchers built on top of `probe()`:

```ts
expectValidVideoFile(filePath, expectations: {
  minDurationSeconds?: number;    // default 10
  minSizeBytes?: number;           // default 1_000_000
  audioLanguages?: string[];       // assert each is present in stream tags
  expectSubtitles?: boolean;       // assert at least one subtitle stream
  maxVideoWidth?: number;          // for 'worst' quality runs: e.g. 1280
})
```

Assertions (all soft-fail and reported together at the end of a test):
- File exists & size ≥ `minSizeBytes`.
- Exactly one `codec_type=video` stream; `width > 0` and `height > 0`; `width ≤ maxVideoWidth` if set.
- ≥ 1 `codec_type=audio` stream.
- `format.duration` parses to a positive number ≥ `minDurationSeconds`.
- If `audioLanguages` provided: for each requested language, at least one audio stream has matching `tags.language` (BCP-47 tolerant).
- If `expectSubtitles`: ≥ 1 `codec_type=subtitle` stream.

### `helpers/paths.ts` — output file resolution
Output paths follow [CLAUDE.md](CLAUDE.md) §"File Organization":
- Movies: `target/e2e/movies/*.mp4` — match by glob using a substring of the title.
- TV: `target/e2e/tvshows/<Show>/Season 01/*.mp4`.

A helper `findDownloadedFile(taskKind, titleHint): Promise<string>` polls the filesystem after `COMPLETED` for up to 5 s (file appears slightly after status flips, due to async rsync).

---

## 6. Fixture file

`e2e/fixtures/content.json`:
```json
{
  "vixsrcMovie":  { "query": "fight club",        "source": "vixsrc",  "type": "MOVIE", "titleHint": "Fight Club" },
  "vixsrcTv":     { "query": "rick and morty",    "source": "vixsrc",  "type": "TV",    "titleHint": "Rick and Morty",   "season": 1, "episode": 1 },
  "raiplayMovie": { "query": "cosmonauta",        "source": "raiplay", "type": "MOVIE", "titleHint": "Cosmonauta" },
  "raiplayTv":    { "query": "rocco schiavone",   "source": "raiplay", "type": "TV",    "titleHint": "Rocco Schiavone",  "season": 1, "episode": 1 }
}
```

Tests import this file and reference entries by key — title changes upstream are a one-line fixture edit.

---

## 7. Test specs

Each test follows the same shape: navigate → search → enqueue → poll queue → assert file via ffprobe.

### `tests/01-smoke.spec.ts`
- Open `/`, expect `#search-input` to be visible.
- Navigate to `/downloads`, expect `#queue-grid` to be visible.
- Navigate to `/settings`, expect `#settings-view` to be visible.
- No download; runtime < 30 s.

### `tests/02-movie-vixsrc.spec.ts`
1. `searchAndPickFirst('fight club', 'vixsrc', 'MOVIE')`
2. Open dialog, `enqueueMovie({ quality: 'worst' })`
3. `gotoQueue()`, `findLatestTaskId({ source: 'vixsrc' })`
4. `waitForStatus(taskId, ['DOWNLOADING'])` then `waitForStatus(taskId, ['COMPLETED'])` with a generous timeout
5. `findDownloadedFile('movies', 'Fight Club')`
6. `expectValidVideoFile(path, { minDurationSeconds: 60, maxVideoWidth: 1280 })`

### `tests/03-tv-vixsrc.spec.ts`
Same shape, but `enqueueEpisode({ season: 1, episode: 1 })`, file lookup under `tvshows/Rick and Morty/Season 01/`. Assert filename contains `S01E01`.

### `tests/04-movie-raiplay.spec.ts` and `tests/05-tv-raiplay.spec.ts`
Same shape using the raiplay fixtures. These tests `test.skip()` themselves if `RAIPLAY_USERNAME` is not set in the environment — keeps the suite green for contributors without RaiPlay creds.

### `tests/06-cancel.spec.ts`
1. Enqueue the vixsrc movie (smallest-runtime fixture chosen on purpose).
2. `waitForStatus(taskId, ['DOWNLOADING'])` — confirms ffmpeg is up.
3. Wait an additional 3 s so at least one progress event has fired (so we exercise the *cancel-mid-progress* path, not the *cancel-immediately* path).
4. `cancelTask(taskId)`.
5. `waitForStatus(taskId, ['CANCELLED'])` within 15 s.
6. Verify no orphan ffmpeg processes: `pgrep -f "ffmpeg.*<taskId>"` returns empty. (Implementation: a small Node helper running `ps`/`pgrep`.)
7. Verify no orphan `.tmp` file remains under `target/e2e/temp/`.

The cancel test runs **last** so it doesn't poison earlier tests; Playwright respects file order with `fullyParallel: false`.

---

## 8. Documentation

### README addition
A new "Running E2E tests" section:
```markdown
## Running E2E tests

Local-only suite that drives the real UI against the real backend.
Takes ~15 minutes and downloads ~2 GB of video at lowest quality.

1. Install Node 20+ and `ffprobe` (bundled with ffmpeg).
2. `cd e2e && npm install && npx playwright install chromium`
3. `cp .env.e2e.example .env.e2e` and fill in `TMDB_API_KEY`
   (and RaiPlay creds if you want those scenarios to run).
4. `npm run test`

Traces, screenshots, and the app log on failure are written to
`e2e/test-results/`. Set `KEEP_E2E_ARTIFACTS=1` to also retain
`target/e2e/` (downloads and SQLite DB) for debugging.
```

### `e2e/README.md`
Short doc explaining the helper structure, how to add a new scenario, and how to debug a flaky test (`npm run test:ui`, trace viewer, where logs live).

---

## 9. Execution order (PR-by-PR)

To keep reviews small, ship in stages. Each stage is independently verifiable.

**PR 1 — Scaffold + smoke test only**
- §0 preflight script
- §1 directory layout
- §2 app boot scripts
- §3 Playwright config
- §4 `data-testid` for `SearchView`, `DownloadQueueView`, `SettingsView` root only (no card / dialog ids yet — smoke test doesn't need them)
- §5 minimal helpers: `app-lifecycle.ts`, `paths.ts`
- §7 `01-smoke.spec.ts`
- §8 README sections
- Verification: `npm run test` runs the smoke test in < 30 s, app boots and tears down cleanly.

**PR 2 — Search + dialog testids + vixsrc movie scenario**
- Remaining `data-testid` additions to `SearchResultCard` and queue rows
- `helpers/search.ts`, `helpers/queue.ts`, `helpers/ffprobe.ts`, `helpers/assertions.ts`
- `02-movie-vixsrc.spec.ts`
- Verification: full vixsrc movie download completes and is ffprobed green.

**PR 3 — TV vixsrc scenario**
- `03-tv-vixsrc.spec.ts`
- Any `data-testid` refinement needed for the TV episode dialog flow.

**PR 4 — RaiPlay scenarios**
- `04-movie-raiplay.spec.ts`, `05-tv-raiplay.spec.ts`
- `test.skip()` guard for missing RaiPlay creds.

**PR 5 — Cancel scenario**
- `06-cancel.spec.ts`
- Orphan-process / orphan-temp-file checks.

Don't bundle these into one mega-PR — each later stage builds confidence that the earlier scaffold actually works against the real app, and the testid changes are easier to review in context.

---

## 10. Verification checklist (post-PR-1)

Before declaring PR 1 done:

- [ ] `e2e/scripts/preflight.sh` exits 0 on a clean machine with deps installed; exits 1 with a helpful message when each dep is missing (test by temporarily renaming `ffprobe` on `PATH`).
- [ ] `npm run test` from `e2e/` succeeds end-to-end with only the smoke test.
- [ ] App startup log appears in `target/e2e/app.stdout.log`; app is reachable at `localhost:8089`.
- [ ] After `npm run test`, no Java process is left running (`lsof -i :8089` empty).
- [ ] `target/e2e/` is wiped on success, preserved when `KEEP_E2E_ARTIFACTS=1`.
- [ ] Running `npm run test` twice back-to-back works; the second run isn't polluted by the first.
- [ ] Existing `mvn clean package` still passes — the testid additions don't break the production build.

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| First-run Vaadin frontend compile takes > 90 s | Bump health-check timeout; or pre-warm by running `mvn vaadin:prepare-frontend` once in preflight. |
| TMDB rate limits hit during repeated local runs | Don't hammer it; suite runs 4 searches/run which is well below limits. If hit, suite fails clearly on the search step rather than mysteriously. |
| Cloudflare challenges from real machine IPs | Already handled by `CloudflareInterceptor` in production code. If they escalate, the suite reveals the regression — which is the point. |
| Downloaded file paths differ from expected glob | `findDownloadedFile` logs the directory contents on failure so the glob can be tightened. |
| Multiple tasks confuse `findLatestTaskId` | Suite uses one task at a time (`PARALLEL_DOWNLOADS=2` is a safety margin, not a usage pattern); plus each test runs in sequence. |
| `data-testid` collisions with Vaadin internals | Verified during PR 1: spot-check rendered DOM with browser devtools to confirm ids land where expected. |

---

## 12. Out of scope (explicit)

To prevent scope creep mid-execution:

- API/integration tests (separate effort).
- CI integration (deferred — see strategy §8).
- Visual regression / screenshot diffing.
- Multi-language audio assertions beyond "at least one audio stream exists" (the language list intersects with what each source actually serves at `worst` quality; assertions there would be flaky).
- Subtitle correctness beyond "subtitle streams exist if requested".
- Settings view interaction tests (smoke test verifies the route loads; deeper coverage not needed yet).
- Retry-after-failure flow.
- Cross-browser (Firefox / WebKit).
