# E2E Testing Strategy — Brainstorm

> Status: brainstorming / pre-decision. Goal: agree on tooling, scope, and the tradeoffs of running tests against the real, unmocked app before writing any test.

## 1. Goals & scope

- **UI-only**. API and integration tests are out of scope for this effort.
- **Run against the real application**: real TMDB, real vixsrc.to / RaiPlay, real ffmpeg, real rsync, real filesystem, real SQLite. No stubs, no shims.
- **Regression safety** for user-facing flows after changes to views, the queue, SSE wiring, or extractor logic.
- One Chromium engine. No cross-browser matrix.

### Honest tradeoff this implies

Running against the live stack means the suite is:
- **Slow** — a real download is minutes long even for the smallest content.
- **Flaky by nature** — TMDB/vixsrc availability, Cloudflare mood, network speed all become test variables.
- **Not suitable as a per-PR CI gate.** This is a **pre-release / nightly / on-demand** suite, run locally or on a self-hosted runner with internet, a TMDB key, and a real download path.
- **Stateful** — leaves files on disk and rows in SQLite; needs a cleanup strategy.

If we want a fast PR-blocking gate later, we'd revisit with a separate isolated-suite effort. For now, accept the tradeoffs above as the price of "real app, real signal."

---

## 2. Tooling

**Playwright (TypeScript)** — recommended.

- Best-in-class auto-wait — important because real downloads have non-deterministic timings; we'll be polling DOM state a lot.
- Handles Vaadin's Shadow DOM (`locator.locator()` pierces shadow roots, or use `>>>` in CSS selectors).
- EventSource / SSE works natively, so we can either observe UI updates or, for assertions about progress events, tap the same `/api/progress/stream` the UI uses.
- Trace viewer + video recording on failure is invaluable when something flakes overnight.
- Headless in CI, headed for local debugging.

Alternatives considered & rejected for this scope:
- **Vaadin TestBench** — commercial, slower iteration, weaker debugging story than Playwright traces.
- **Selenide/Selenium** — weaker Shadow DOM ergonomics; Playwright is just better in 2026.
- **Cypress** — Shadow DOM support exists but is fiddlier; SSE/EventSource handling is awkward.

Layout: a top-level `e2e/` directory with its own `package.json`, `playwright.config.ts`, `tests/`, and `fixtures/`. Kept separate from the Maven build.

---

## 3. How tests actually run

```
┌────────────────────────────────────────────────────────────┐
│ Developer machine (or self-hosted runner)                  │
│                                                            │
│  1. mvn spring-boot:run    (real app, real config)         │
│         │                                                  │
│         ├── reaches TMDB ────────────► api.themoviedb.org  │
│         ├── reaches vixsrc ──────────► vixsrc.to           │
│         ├── spawns real ffmpeg                             │
│         ├── spawns real rsync                              │
│         └── writes to a *test-only* download path          │
│                                                            │
│  2. npx playwright test  ──drives──►  http://localhost:8080│
└────────────────────────────────────────────────────────────┘
```

Test setup steps the harness must perform:

1. Boot the app with a **dedicated test config** — same code, just different paths and a separate SQLite DB:
   - `DOWNLOAD_MOVIES_PATH=target/e2e/movies`
   - `DOWNLOAD_TV_SHOWS_PATH=target/e2e/tvshows`
   - `DOWNLOAD_TEMP_PATH=target/e2e/temp`
   - `SQLITE_DB_PATH=target/e2e/vixsrc.db`
   - `TMDB_API_KEY` from env / `.env.e2e` (gitignored)
   - `PARALLEL_DOWNLOADS=2` to keep CPU/network sane.
2. Wait for `/actuator/health` (or the root page) before tests start.
3. Run the Playwright suite.
4. **Teardown**: stop the app, wipe `target/e2e/`.

Playwright's `globalSetup` / `globalTeardown` hooks can own (1)–(4) so `npx playwright test` is the only command anyone has to type.

---

## 4. Choosing test content carefully

This is the most important design decision when tests are unmocked. We want content that is:
- **Small** (short runtime → short download).
- **Stable** (won't get pulled from TMDB or vixsrc).
- **Multi-track** for at least one case (to exercise audio/subtitle orchestration).

Maintain a small **"E2E content manifest"** in `e2e/fixtures/content.json`. Selected titles:

| Source | Type | Title (search query) | Episode picked |
|--------|------|----------------------|----------------|
| vixsrc | Movie | Fight Club | — |
| vixsrc | Series | Rick and Morty | S01E01 |
| raiplay | Movie | Cosmonauta | — |
| raiplay | Series | Rocco Schiavone | S01E01 |

Tests drive the **real UI flow**: type the title in the search bar, click the first result, (for series) navigate to S01E01, click Download. No TMDB ids, no REST API shortcuts — same path a user takes.

If any of these disappears upstream or the top search result changes, the manifest is the one place to update.

---

## 5. UI scenarios for v1

Deliberately short. Each one has to earn its slot because each one costs real minutes and real bandwidth.

| # | Scenario | What it proves |
|---|----------|----------------|
| 1 | App boots, search page loads, settings page shows configured paths | Bean wiring, basic routing |
| 2 | Search a known title, results render with poster + metadata | TMDB integration end-to-end |
| 3 | Enqueue **Fight Club** (vixsrc), watch it go QUEUED → EXTRACTING → DOWNLOADING → MERGING → COPYING → COMPLETED, verify final file exists on disk and is non-empty | The full happy path, SSE, ffmpeg, rsync |
| 4 | Enqueue **Rick and Morty S01E01** (vixsrc), verify it lands in `tvshows/Rick and Morty/Season 01/...` | TV path handling |
| 5 | Enqueue **Cosmonauta** (raiplay), verify completion | RaiPlay movie flow |
| 6 | Enqueue **Rocco Schiavone S01E01** (raiplay), verify TV path handling | RaiPlay series flow |
| 7 | Enqueue a movie, then cancel mid-download, verify status CANCELLED and ffmpeg process is gone | Cancellation correctness |

**Dropped from v1**: the "fail extraction" scenario. Reliably triggering an extraction failure in an unmocked test is fragile — a bogus TMDB id fails at the TMDB step, not at extraction, so it exercises the wrong code path. A real TMDB id that isn't on vixsrc would work but isn't guaranteed to stay that way. Revisit later if needed; for now we trust unit/integration coverage of the failure paths.

Skip for v1: visual regression, dark mode, keyboard nav, multi-language audio assertion (downloads work, but asserting on track metadata needs ffprobe — defer).

All downloads run at the **lowest available quality** (`worst`) to minimize runtime, bandwidth, and disk usage. Quality selection is not what these tests are verifying.

**Expected suite runtime: ~10–20 minutes** with 4 real downloads at lowest quality. That's the price of unmocked.

---

## 6. Selectors & Vaadin

Vaadin views render inside Shadow DOM, which makes CSS selectors fragile.

**Decision: use `data-testid` attributes** on the few elements tests interact with: search input, search-result cards, season/episode rows, download button, queue rows, cancel button, status cell.

Naming convention:
- `data-testid="search-input"`
- `data-testid="search-result-{index}"` (or by title slug)
- `data-testid="download-button"`
- `data-testid="queue-row-{taskId}"`
- `data-testid="queue-row-{taskId}-status"`
- `data-testid="queue-row-{taskId}-cancel"`

No meaningful drawbacks: attributes are invisible to users, don't affect styling/behavior, and `data-testid` is the industry-standard pattern designed precisely for this purpose.

---

## 7. Cleanup & idempotency

Each test must:
- Use a **unique tag in the filename or a unique TMDB combo** so two runs don't collide.
- Tear down: delete the downloaded file, delete the SQLite row (or wipe the DB between runs).

Simplest approach for v1: **fresh DB and fresh `target/e2e/` on every full suite run**. Don't try to make individual tests independent of each other — let the suite be the unit of isolation. Tests within the suite can share the booted app but should not share state assumptions.

---

## 8. CI considerations

**Decision: local-only for v1.** No CI integration yet — we'll revisit once the suite is stable and we know its true runtime / flakiness profile.

What "local-only" means concretely:
- A documented command (e.g. `npm run e2e` inside `e2e/`, or a top-level `./run-e2e.sh`) that boots the app, runs Playwright, tears down.
- Run before tagging a release; run after risky refactors.
- On failure, Playwright traces + screenshots + captured `logs/application.log` are written to `e2e/test-results/` for debugging.

Room for CI later:
- A self-hosted runner (home/lab machine) with TMDB key as a repo secret and real network egress.
- Triggered via `workflow_dispatch` and/or nightly schedule.
- Not on GitHub-hosted runners — outbound traffic to vixsrc from cloud IPs will hit Cloudflare challenges and TMDB rate limits.

---

## 9. Open questions

- **Cancellation timing** (scenario #7): how late in the download do we cancel? Too early and we may cancel before ffmpeg starts; too late and we waste minutes. Plan: wait for `DOWNLOADING` status + at least one progress event, then cancel.
- **Search disambiguation**: if a title's top result on TMDB ever changes (e.g. a remake takes the #1 slot), tests pick the wrong item. Acceptable risk for v1 — if it happens, update the manifest. Alternative is asserting on a year or expected metadata in the result card before clicking.

---

## 10. Suggested next step

If this direction is right, the first concrete PR would be:

1. Scaffold `e2e/` with Playwright + `globalSetup` that boots the app via `mvn spring-boot:run` with the test config, and `globalTeardown` that stops it and wipes `target/e2e/`.
2. Add `data-testid` attributes to the handful of elements scenario #1 needs.
3. Implement scenario #1 (smoke test: app boots, search & settings pages render).
4. Document the local run command and the TMDB key requirement in README.

Scenarios #2–6 land in follow-up PRs once the scaffold is proven.
