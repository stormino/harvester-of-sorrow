# Multi-Source Support + RaiPlay Integration

## Overview

Today the downloader is hard-wired to **vixsrc.to**: search uses TMDB and then probes vixsrc for availability, extraction calls vixsrc-specific HTML/regex strategies, and the `DownloadTask` model only carries a `tmdbId`. We want to:

1. Generalize the pipeline to support **multiple sources** (VixSrc, RaiPlay, future ones).
2. Add **RaiPlay** as a second source. Example content URL:
   `https://www.raiplay.it/video/2018/12/COSMONAUTA-f5cbe4fd-7eb2-490f-af5b-ff0cd2009973.html`
3. Show the **source as a tag** on each search result card and propagate it through the download queue / persistence.

The plan is incremental: introduce a source abstraction first (no behavior change), then plug in RaiPlay.

---

## 1. Domain model changes

### 1.1 New `MediaSource` enum

`src/main/java/com/github/stormino/model/MediaSource.java`

```java
public enum MediaSource {
    VIXSRC("VixSrc"),
    RAIPLAY("RaiPlay");

    private final String displayName;
    // + getter, lookup by name (case-insensitive)
}
```

Rationale: a typed enum is cheaper than free-form strings and makes the UI tag / persistence column easy to render.

### 1.2 Generalize identifiers

VixSrc content keys off TMDB integer IDs. RaiPlay content keys off a **UUID string** (`f5cbe4fd-7eb2-490f-af5b-ff0cd2009973` from the example URL). We need to carry both without forcing one into the other.

Update `ContentMetadata` (`model/ContentMetadata.java`):

- Add `MediaSource source` (required).
- Add `String sourceId` — the canonical ID for that source (TMDB id as string for VixSrc, UUID for RaiPlay).
- Keep `Integer tmdbId` as an optional convenience for the VixSrc/TMDB path (so existing TMDB-based code keeps compiling).
- For RaiPlay results, also keep the program/series UUID and (if applicable) episode UUID.

Update `DownloadTask` (`model/DownloadTask.java`):

- Add `MediaSource source` (default `VIXSRC` for backward compat with persisted rows).
- Add `String sourceId`.
- `tmdbId` becomes nullable (RaiPlay items won't have one unless we map them).
- `getDisplayName()` falls back to source-provided title; no other behavior change.

### 1.3 Persistence migration

New file `src/main/resources/db/migration/V2__multi_source.sql`:

```sql
ALTER TABLE download_task ADD COLUMN source     TEXT NOT NULL DEFAULT 'VIXSRC';
ALTER TABLE download_task ADD COLUMN source_id  TEXT;
-- Backfill source_id from tmdb_id for existing rows
UPDATE download_task SET source_id = CAST(tmdb_id AS TEXT) WHERE source_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_task_source ON download_task(source);
```

Update `DownloadTaskRecord` and the mapper in `TaskPersistenceService` to read/write the two new columns. `tmdbId` stays nullable.

---

## 2. Source abstraction (Strategy pattern)

### 2.1 Interface

`src/main/java/com/github/stormino/service/source/MediaSourceProvider.java`

```java
public interface MediaSourceProvider {
    MediaSource source();

    /** Free-text search; returns metadata enriched with sourceId + source. */
    List<ContentMetadata> search(String query, ContentTypeFilter filter);

    /** Lightweight availability check used to filter search results. */
    AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages);

    /** Resolve the playlist URL for a movie / single film. */
    Optional<PlaylistInfo> getMoviePlaylist(DownloadTask task, String language);

    /** Resolve the playlist URL for a TV episode (if the source supports series). */
    Optional<PlaylistInfo> getTvPlaylist(DownloadTask task, String language);

    /** Languages this source natively supports (used for the dialog). */
    Set<String> supportedLanguages();
}
```

`ContentTypeFilter` is a small enum: `MOVIES`, `TV`, `BOTH`.

### 2.2 Registry

`MediaSourceRegistry` is a `@Component` that injects `List<MediaSourceProvider>` and exposes:

- `MediaSourceProvider get(MediaSource source)`
- `List<MediaSourceProvider> all()`
- `List<MediaSourceProvider> enabled()` — driven by config (`vixsrc.sources.enabled: [VIXSRC, RAIPLAY]`).

This is what the queue / search will talk to instead of `VixSrcExtractorService` directly.

### 2.3 Refactor existing VixSrc code into a provider

Create `service/source/VixSrcSourceProvider`:

- Wraps the existing `VixSrcExtractorService`, `VixSrcAvailabilityService`, and `TmdbMetadataService` (TMDB search remains the way users find VixSrc content).
- `search(...)` calls TMDB and tags every result with `source=VIXSRC`, `sourceId=String.valueOf(tmdbId)`.
- `getMoviePlaylist` / `getTvPlaylist` delegate to the existing extractor (signature accepts a `DownloadTask`, the provider pulls `tmdbId` from it).

No logic changes to extraction itself in this step — only relocation behind the interface.

### 2.4 Wire through `DownloadQueueService`

In `processTaskAsync`:

```java
MediaSourceProvider provider = registry.get(task.getSource());
Optional<PlaylistInfo> playlistInfo = task.getContentType() == ContentType.TV
    ? provider.getTvPlaylist(task, primaryLang)
    : provider.getMoviePlaylist(task, primaryLang);
```

Same swap inside `TrackDownloadOrchestrator.downloadTrackAsync` (the per-track playlist refetch must also go through the provider). The orchestrator currently builds an embed URL string for the `Referer` header — move that into `PlaylistInfo` so each provider owns it.

`PlaylistInfo` gains a `String referer` field; existing VixSrc code populates it with the embed URL it currently builds inline.

---

## 3. RaiPlay provider

RaiPlay exposes content as JSON behind the `.html` page. Two pieces of public information we'll rely on:

1. **Content JSON**: the page `/video/2018/12/COSMONAUTA-<uuid>.html` has a sibling JSON descriptor at `/video/2018/12/COSMONAUTA-<uuid>.json` that contains a `video.content_url` (a relinker URL) and a `video.contentItem` block with title / year / programName / etc. Confirm the exact path by hitting the JSON endpoint during implementation; if the path differs, scrape the HTML for the `data-video-json` attribute (RaiPlay's standard pattern).
2. **Relinker**: the `content_url` (e.g. `https://mediapolisvod.rai.it/relinker/relinkerServlet.htm?cont=...&output=64`) responds with either an XML payload pointing to an HLS `.m3u8`, or with a 302 to it. Output `64` returns JSON with the m3u8 URL; output `45` returns XML. We'll request `output=64`.
3. **Search**: RaiPlay's autocomplete API works well: `https://www.raiplay.it/atomatic/raiplay-search-service/api/v1/msearch?q=<query>&pl=raiplay&size=20`. Each hit has `id`, `path_id` (the page path), `type` (`video`, `programma`, etc.), `program_name`, `subtitle`, `year`. For films, `type == "video"` with a single playable item; for series, `type == "programma"` and we have to drill into seasons via `https://www.raiplay.it<path_id>` + the program JSON to enumerate episodes.

> If during implementation any of these endpoints prove unstable, we fall back to scraping the HTML page for the `__INITIAL_STATE__` blob — same data, different shape. Keep the parsing layer behind `RaiPlayApiClient` so we can swap it.

### 3.1 New files

```
service/source/raiplay/
    RaiPlaySourceProvider.java        // implements MediaSourceProvider
    RaiPlayApiClient.java             // OkHttp + JSON parsing (Jackson)
    RaiPlaySearchResult.java          // DTO for search hits
    RaiPlayContentDescriptor.java     // DTO for the per-item content JSON
config/RaiPlayProperties.java         // base URL, search URL, timeout
```

`application.yml`:

```yaml
vixsrc:
  sources:
    enabled: [VIXSRC, RAIPLAY]

raiplay:
  base-url: https://www.raiplay.it
  search-url: https://www.raiplay.it/atomatic/raiplay-search-service/api/v1/msearch
  user-agent: ${vixsrc.extractor.user-agent}
  timeout-seconds: 15
```

### 3.2 Implementation notes

- **`search(query, filter)`**:
  - Call the msearch endpoint with `q=query`.
  - Map each hit to `ContentMetadata` with `source=RAIPLAY`, `sourceId = hit.path_id` (full path including UUID — that's the canonical identifier we'll need later), `title`, `year`, `overview = hit.description`.
  - Map RaiPlay's `type` to `MOVIE` or `TV` and apply the `filter`.
- **`checkAvailability`**: HEAD/GET the `path_id` JSON and confirm we can resolve a `content_url`. RaiPlay items are mostly geo-restricted to Italy — surface this in logs (the request will 403 outside IT) but treat it like a normal availability miss.
- **`getMoviePlaylist`**:
  1. GET `<base>/<path_id>.json`.
  2. Read `video.content_url` (or `video.contentItem.video.content_url`).
  3. GET that URL with `output=64` parameter; parse the JSON and pull the m3u8 URL out of `video.[0].url` (path TBD by the response shape — pin it down during dev).
  4. Build `PlaylistInfo(url=m3u8, language=lang, referer=<page url>, verified=true if we can verify against `#EXTM3U`)`. Reuse the verification helper from `VixSrcExtractorService` — extract it into `service/source/PlaylistVerifier` so both providers can call it.
- **`getTvPlaylist`**: For series, the `DownloadTask` carries `season` + `episode`. Resolve the program JSON, walk seasons → episodes, find the matching episode UUID, then run the same pipeline as `getMoviePlaylist` for that episode's `path_id`. If RaiPlay numbering doesn't align with TMDB's, prefer RaiPlay's own `season_number` / `episode_number` (we're already source-native here — TMDB has no role).
- **`supportedLanguages`**: `Set.of("it")`. RaiPlay only ships Italian audio; the language selector in the dialog should reflect this.

### 3.3 Track orchestrator implications

RaiPlay HLS streams typically embed audio inside the master playlist (no separate audio renditions, no subtitle tracks of the kind VixSrc serves). Two options:

- **Option A (preferred):** keep the orchestrator generic. Let the strategy detect "no separate audio renditions" and skip audio sub-tasks (today's code already tolerates `NOT_FOUND` audio — confirm path 119 of `TrackDownloadOrchestrator` handles "all audio NOT_FOUND" gracefully; it does).
- **Option B:** add a `MediaSourceProvider.trackPlan(DownloadTask)` method that returns the list of sub-task types to spawn. Implement only if Option A turns out to misbehave with RaiPlay streams.

Plan to start with A and revisit only if we hit failures during testing.

---

## 4. Search & UI

### 4.1 SearchView

`view/SearchView.java`:

- Replace the direct `tmdbService.searchMovies` / `searchTvShows` calls with a parallel fan-out across `registry.enabled()`:
  ```java
  List<CompletableFuture<List<ContentMetadata>>> futures = registry.enabled().stream()
      .map(p -> CompletableFuture.supplyAsync(() -> p.search(query, filter)))
      .toList();
  ```
- Per-result availability check still runs through the corresponding provider (`provider.checkAvailability`).
- Merge results, dedup nothing (different sources may legitimately list the same title).

### 4.2 SearchResultCard — source tag

`view/component/SearchResultCard.java` already renders a small badge row next to the title. Add a new `Span` that shows the source name:

```java
Span sourceTag = new Span(content.getSource().getDisplayName());
sourceTag.addClassNames(LumoUtility.FontSize.XSMALL);
sourceTag.getStyle()
    .set("background", switch (content.getSource()) {
        case VIXSRC  -> "#1976D2";
        case RAIPLAY -> "#0066B3"; // RaiPlay blue
    })
    .set("color", "#fff")
    .set("padding", "0.15rem 0.4rem")
    .set("border-radius", "0.25rem")
    .set("font-weight", "600");
titleRow.add(sourceTag);
```

The card "open TMDB page" click handler currently fires for every card — gate it on `source == VIXSRC` (or generalize to "external page URL" exposed by the provider, returning the RaiPlay page URL for RaiPlay items).

### 4.3 Download dialog

`SearchResultCard.openDownloadDialog`:

- Restrict the language selector to `provider.supportedLanguages()` (RaiPlay → only `it`).
- Pass `source` + `sourceId` through `DownloadHandler.onDownload`. Today's signature is `(content, type, season, episode, languages, quality)` — `content` already carries source/sourceId once §1.2 lands, so no signature change needed; just make sure `SearchView.handleDownload` propagates them into `addDownload`.

### 4.4 DownloadQueueView

Add a small source badge on each queue row, identical styling to the search card. (`view/DownloadQueueView.java` — a one-line addition next to the existing title cell.)

---

## 5. API surface

`controller/DownloadController.java`:

- `GET /api/search?query=...&type=movies|tv|both` — new unified endpoint that fans out across providers and returns enriched `ContentMetadata` (with `source`, `sourceId`).
- Keep the existing `/api/search/movies` and `/api/search/tv` endpoints but have them delegate to the unified path with the appropriate filter (preserves backward compatibility).
- `POST /api/download/movie` and `/api/download/tv` gain optional `source` + `sourceId` query params. Default `source=VIXSRC` and treat `tmdbId` as the source ID when `sourceId` is absent.

`DownloadQueueService.addDownload` signature gains `MediaSource source, String sourceId`. All call sites (controller + `SearchView`) updated.

---

## 6. Testing strategy

Unit tests (per existing `src/test/java` layout):

- `RaiPlayApiClientTest` — feed canned JSON fixtures (search response, content descriptor, relinker JSON) into a mocked OkHttp client; assert parsed `ContentMetadata` / playlist URL.
- `MediaSourceRegistryTest` — verify lookup + enabled filtering.
- `VixSrcSourceProviderTest` — smoke test that the provider delegates correctly (mock the existing services).
- `DownloadQueueServiceTest` — add cases for `source=RAIPLAY` to confirm the queue routes to the right provider.

Manual verification:

1. Search for "cosmonauta" → expect a RaiPlay card with year 2018, source tag "RaiPlay".
2. Click download → dialog shows Italian as the only language.
3. Queue runs end-to-end: extract relinker → resolve m3u8 → ffmpeg downloads → file lands in `moviesPath`.
4. Search for "fight club" → expect VixSrc card to still work and show source tag "VixSrc". No regression.
5. Restart the app → existing in-flight tasks restored as FAILED with proper `source` populated; QUEUED tasks re-queued.

---

## 7. Out of scope (explicit)

- TMDB enrichment of RaiPlay results (matching RaiPlay programs to TMDB IDs). Nice-to-have, but the source's own metadata is sufficient for filename generation.
- Subtitle download from RaiPlay (no separate WebVTT track exposed; if any are inlined we get them for free in the m3u8).
- Geo-bypass for non-Italian users — out of scope, log and surface "not available in your region" gracefully.
- Adding more sources (e.g., Mediaset Infinity, RaiPlay Sound) — the abstraction supports it; doing it is a separate task.

---

## 8. Rollout order

Recommended commit-by-commit order so each step is reviewable on its own:

1. Add `MediaSource` enum + plumb `source`/`sourceId` through `ContentMetadata`, `DownloadTask`, persistence (V2 migration). All new fields default to VixSrc — zero behavior change.
2. Introduce `MediaSourceProvider` interface + `MediaSourceRegistry`. Refactor existing VixSrc code into `VixSrcSourceProvider`. `DownloadQueueService` and `TrackDownloadOrchestrator` go through the registry.
3. Add the source tag to `SearchResultCard` + queue rows.
4. Implement `RaiPlaySourceProvider` + `RaiPlayApiClient` with fixture-based tests.
5. Wire RaiPlay into the registry, `SearchView` fan-out, and the controller's `source` parameter.
6. Manual end-to-end verification with the COSMONAUTA example.
