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

### 1.2 Generalize identifiers — typed `SourceMetadata`

VixSrc content keys off TMDB integer IDs. RaiPlay content keys off **multiple UUIDs** (program UUID, season slug/number, episode UUID — `f5cbe4fd-7eb2-490f-af5b-ff0cd2009973` is the episode UUID from the example URL). Future sources will likely have their own shape too.

Instead of stuffing everything into a single `sourceId` string we keep a typed, polymorphic `SourceMetadata` blob alongside `MediaSource`:

```java
// model/source/SourceMetadata.java
@JsonTypeInfo(use = NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = VixSrcMetadata.class,  name = "VIXSRC"),
    @JsonSubTypes.Type(value = RaiPlayMetadata.class, name = "RAIPLAY"),
})
public sealed interface SourceMetadata permits VixSrcMetadata, RaiPlayMetadata {
    MediaSource source();
}

public record VixSrcMetadata(int tmdbId, Integer season, Integer episode) implements SourceMetadata {
    public MediaSource source() { return MediaSource.VIXSRC; }
}

public record RaiPlayMetadata(
        String pathId,         // e.g. "/video/2018/12/COSMONAUTA-<uuid>.html"
        String contentUuid,    // the UUID at the end of the path
        String programUuid,    // null for stand-alone films
        String seasonId,       // null for films
        String episodeUuid     // null for films
) implements SourceMetadata {
    public MediaSource source() { return MediaSource.RAIPLAY; }
}
```

A sealed interface + records gives us exhaustive `switch` on the consumer side and keeps Jackson polymorphism explicit.

Update `ContentMetadata` (`model/ContentMetadata.java`):

- Add `MediaSource source` (required).
- Add `SourceMetadata sourceMetadata` (required).
- Drop the bare `Integer tmdbId` field over time — for now keep it nullable as a convenience for the existing TMDB code paths, but new code reads it from the metadata (`((VixSrcMetadata) m.getSourceMetadata()).tmdbId()`).

Update `DownloadTask` (`model/DownloadTask.java`):

- Add `MediaSource source` (default `VIXSRC` so legacy rows materialize correctly).
- Add `SourceMetadata sourceMetadata`.
- `tmdbId` / `season` / `episode` stay on the task as nullable convenience fields populated from the metadata at construction time, since lots of code (`getDisplayName`, output-path generation, the controller's `season`/`episode` query params) reads them. Treat them as **derived** from `sourceMetadata` — single source of truth is the metadata blob.

### 1.3 Persistence — JSON-as-TEXT with Jackson polymorphism

A note on column type. SQLite has no real `JSON` type — `JSON` is just a hint, the storage is still TEXT, and there's no built-in validation. Declaring `JSON` instead of `TEXT` would only be cosmetic. JSONB (3.45+) is a separate binary representation that requires going through `jsonb_*` functions on read/write, and we never query *into* this blob from SQL — we read/write the whole thing from Java. So we declare `TEXT` and add an explicit `CHECK (json_valid(...))` constraint to actually validate writes.

`src/main/resources/db/migration/V2__multi_source.sql`:

```sql
ALTER TABLE download_task ADD COLUMN source           TEXT NOT NULL DEFAULT 'VIXSRC';
ALTER TABLE download_task ADD COLUMN source_metadata  TEXT
    CHECK (source_metadata IS NULL OR json_valid(source_metadata));
-- Backfill: build VixSrc metadata for existing rows from tmdb_id/season/episode.
-- json_object is part of SQLite's JSON1 extension.
UPDATE download_task
   SET source_metadata = json_object(
       'type',    'VIXSRC',
       'tmdbId',  tmdb_id,
       'season',  season,
       'episode', episode
   )
 WHERE source_metadata IS NULL;
CREATE INDEX IF NOT EXISTS idx_task_source ON download_task(source);
```

Mapping wiring — Jackson polymorphism, no Spring Data JDBC converter:

- `SourceMetadata` is already annotated with `@JsonTypeInfo(use = NAME, property = "type")` + `@JsonSubTypes` (see §1.2), which gives us discriminated (de)serialization out of the box.
- Keep `DownloadTaskRecord.sourceMetadata` as a raw **`String`** (matches the column). Spring Data JDBC stays out of polymorphism entirely.
- Do the conversion at the persistence boundary inside `TaskPersistenceService`:
  ```java
  // record -> domain
  task.setSourceMetadata(
      record.getSourceMetadata() == null
          ? null
          : objectMapper.readValue(record.getSourceMetadata(), SourceMetadata.class));

  // domain -> record
  record.setSourceMetadata(
      task.getSourceMetadata() == null
          ? null
          : objectMapper.writeValueAsString(task.getSourceMetadata()));
  ```
- One `ObjectMapper` bean (the existing Spring Boot autoconfigured one) is enough — Jackson's polymorphic annotations don't need any module registration when the subtypes are listed via `@JsonSubTypes`.

Why not Spring Data JDBC converters: a `Converter<String, SourceMetadata>` on top of Jackson's polymorphism stacks two discrimination mechanisms that have to agree, and SDJ's resolution is fiddly with sealed/parameterized types. The mapper-in-`TaskPersistenceService` path is single-source-of-truth, fully explicit, and trivial to grep / step through when something looks off.

`DownloadTaskRecord` gets the two new fields. Existing `tmdbId` / `season` / `episode` columns and fields are kept (still populated from the metadata at write time, still useful for queries / display).

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
  user-agent: ${vixsrc.extractor.user-agent}   # already a full Chrome 120 Windows UA
  timeout-seconds: 15
```

> Note on UA: `vixsrc.extractor.user-agent` is already
> `"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"`,
> which is what we want — nothing identifies us as a downloader. Reuse it as-is for RaiPlay.

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

### 3.3 Track orchestrator — switch to playlist-driven sub-task plan

**Reality check:** RaiPlay does ship multi-track HLS. The Hateful Eight (`/video/2018/09/FILM-The-Hateful-Eight-13e41e79-2f3f-4428-981a-3c142b5ef33a.html`) exposes:

- 3 audio renditions: Italian, Original (English), Audio Description (IT)
- 1 subtitle rendition: Italian

The current orchestrator (`TrackDownloadOrchestrator.initializeTrackSubTasks`) is **language-driven**: it spawns 1 video + N audio + N subtitle sub-tasks based on `task.languages`. That's a poor fit for both:

- **RaiPlay**: user can't know in advance what languages or "Audio Description" tracks exist. Also the language code on the rendition (`ita`, `und`, etc.) doesn't always match what a user types.
- **VixSrc with multiple audio variants**: same problem in the limit.

Good news: `HlsParserService` already discovers everything we need. `HlsPlaylist` exposes `List<AudioTrack> {url, language, name, groupId}` and `List<SubtitleTrack> {url, language, name}`, populated from `#EXT-X-MEDIA:TYPE=AUDIO` / `TYPE=SUBTITLES`. We just don't use them for sub-task planning.

**New plan — playlist-first:**

1. Add `MediaSourceProvider.resolveMaster(DownloadTask, String primaryLanguage)` returning `Optional<ResolvedMedia>` where:
   ```java
   record ResolvedMedia(
       PlaylistInfo playlist,        // master playlist URL + referer + verified flag
       HlsPlaylist parsed,           // already-parsed master with audio/sub renditions
       Set<String> defaultAudioLanguages  // hint when the source has a "preferred" audio
   ) {}
   ```
2. `TrackDownloadOrchestrator.downloadWithTracks` is restructured:
   - Step 1 (was: nothing): call `provider.resolveMaster(task, primaryLanguage)`. Bail out as `FAILED` if empty.
   - Step 2: build sub-tasks from the parsed renditions:
     - 1 video sub-task (always).
     - 1 audio sub-task per discovered `AudioTrack` whose `language` is in `task.languages` — **plus** any track with `name` indicating audio description if the user opted in (default off; new `task.includeAudioDescription` flag, surfaced as a checkbox in the download dialog).
     - 1 subtitle sub-task per discovered `SubtitleTrack` whose `language` is in `task.languages`.
     - If the user requested a language that isn't in the parsed renditions → log + skip (don't fail). If **none** of the requested languages match any audio rendition, fall back to the source's `defaultAudioLanguages` so we always download something playable.
   - Step 3 onward (download tracks, merge, copy) is unchanged. The existing AUDIO/SUBTITLE strategies already accept a playlist URL — we just feed them the rendition URL from the parsed master rather than re-resolving.
3. `DownloadSubTask` gets two optional fields: `String trackName` (e.g. `"Audio Description"`) and `String renditionUrl` (the per-rendition m3u8). They're used by ffmpeg-merge metadata and the queue UI for clarity. Persisted in V3 if needed (or stored only in memory for now and re-derived on restart — easier to skip persistence here since failed-on-restart tasks restart from extraction anyway).

**Why this is also a win for VixSrc:**

- Today VixSrc audio fetching re-resolves the playlist URL once per language inside `downloadTrackAsync`. After this change there's a single `resolveMaster` call up front, and audio/subtitle tracks come from the parsed master directly. Fewer HTTP round-trips, fewer chances for the language `?lang=` query trick to misbehave.
- The "all audio NOT_FOUND => assume embedded audio" hack at line 119 of `TrackDownloadOrchestrator` becomes a clean branch: if `parsed.audioTracks` is empty, we simply don't spawn any audio sub-tasks. No more inferring from failures.

**Provider implementations of `resolveMaster`:**

- `VixSrcSourceProvider`: existing extractor → `PlaylistInfo` → `hlsParserService.parsePlaylist(url, embedUrl)` → wrap.
- `RaiPlaySourceProvider`: relinker → m3u8 → `hlsParserService.parsePlaylist(url, pageUrl)` → wrap. `defaultAudioLanguages = Set.of("ita")`.

### 3.4 UI implications of multi-track discovery

Knowing the renditions only after `resolveMaster` is a small UX wart: the download dialog can't show a definitive language list before the user clicks "Add to Queue". Two acceptable options:

- **Defer-and-display (preferred):** the dialog stays simple (language multiselect + quality + "include audio description" checkbox). After enqueue, the queue row shows the actually-downloaded tracks (we already render sub-tasks). This is what we'll do.
- **Probe-on-open:** call `resolveMaster` when the dialog opens and populate language options from it. Costs an HTTP round-trip per dialog open; defer unless users complain.

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
2. Click download → dialog shows Italian + the "include audio description" checkbox.
3. Queue runs end-to-end: extract relinker → resolve m3u8 → parse master → spawn 1 video + 1 audio (IT) sub-task → ffmpeg merges → file lands in `moviesPath`.
4. Search for "hateful eight" → RaiPlay card. Download with default language "it":
   - Queue should show 1 video sub-task + 1 audio (IT) sub-task + 1 subtitle (IT) sub-task.
   - Re-download with the audio-description checkbox enabled → an extra audio sub-task labelled "Audio Description" appears.
   - Re-download with `it,en` languages → adds the Original/English audio sub-task too.
5. Search for "fight club" → expect VixSrc card to still work and show source tag "VixSrc". Output and merged file identical to before the refactor.
6. Restart the app mid-download → in-flight tasks restored as FAILED with `source` populated correctly; QUEUED tasks re-queued.

---

## 6.5 Verification of RaiPlay assumptions (do this before commit #5)

Everything in §3 about RaiPlay's APIs is **reasoned from RaiPlay's public patterns, not observed end-to-end** in this planning session. RaiPlay is geo-restricted to Italy, so verification has to happen from an Italian network (or VPN to IT). Treat this as a prerequisite for the RaiPlay implementation commit, not the multi-source refactor.

Concretely, capture real responses for the following and drop them under `src/test/resources/raiplay/` as fixtures:

1. **Search**: `GET https://www.raiplay.it/atomatic/raiplay-search-service/api/v1/msearch?q=cosmonauta&pl=raiplay&size=20`
   → confirm hit shape (`id`, `path_id`, `type`, `program_name`, `subtitle`, `year`, `description`).
2. **Film descriptor**: `GET https://www.raiplay.it/video/2018/12/COSMONAUTA-f5cbe4fd-7eb2-490f-af5b-ff0cd2009973.json`
   → confirm `video.content_url` exists and points to a relinker. **If 404**, fetch the `.html` instead and grep for `__INITIAL_STATE__` / `data-video-json` — that's the fallback path. Whichever wins, lock it as the primary path and keep the other as fallback.
3. **Series program page**: pick a series (Hateful Eight is a film — find a real series e.g. *L'amica geniale* or any RaiPlay series). Capture the program JSON and one season's episodes JSON. Confirm episode → `path_id` mapping and that episodes carry `season_number` / `episode_number`.
4. **Relinker**: `GET <content_url>&output=64` → confirm JSON shape, locate the m3u8 URL inside it (likely `video[0].url` or similar). If `output=64` is rejected, try `output=47` (HLS) or no output param.
5. **Master playlist**: `GET <m3u8>` with appropriate `Referer` → confirm it's a master with multiple `#EXT-X-MEDIA:TYPE=AUDIO` lines for the Hateful Eight URL, and that the existing `HlsParserService` parses it correctly. Capture the raw `.m3u8` as a fixture.
6. **Audio Description detection**: in the captured master playlist, note exactly how the audio-description rendition is labelled. Likely candidates:
   - `NAME="Audio Description"` / `"Audiodescrizione"` / `"AD"`
   - `CHARACTERISTICS="public.accessibility.describes-video"`
   - A specific `LANGUAGE` code like `it-AD`
   The matching predicate in the orchestrator (`isAudioDescription(AudioTrack t)`) is tuned to whatever the fixture shows; the dialog checkbox label uses the same wording RaiPlay uses.

These fixtures also become the inputs for `RaiPlayApiClientTest` and a new `HlsAudioDescriptionDetectionTest`. The tests are the contract — if RaiPlay reshapes the API later, the fixtures get re-captured and the test diffs tell us exactly what changed.

If even one of (2) / (4) / (5) doesn't work as planned, surface it before writing the production code and we'll revise §3.

## 7. Out of scope (explicit)

- TMDB enrichment of RaiPlay results (matching RaiPlay programs to TMDB IDs). Nice-to-have, but the source's own metadata is sufficient for filename generation.
- Subtitle download from RaiPlay (no separate WebVTT track exposed; if any are inlined we get them for free in the m3u8).
- Geo-bypass for non-Italian users — out of scope, log and surface "not available in your region" gracefully.
- Adding more sources (e.g., Mediaset Infinity, RaiPlay Sound) — the abstraction supports it; doing it is a separate task.

---

## 8. Rollout order

Recommended commit-by-commit order so each step is reviewable on its own:

1. Add `MediaSource` enum + sealed `SourceMetadata` interface + records. Plumb `source`/`sourceMetadata` through `ContentMetadata`, `DownloadTask`, persistence (V2 migration with JSON1 backfill, Spring Data JDBC converters). All new fields default to VixSrc — zero behavior change.
2. Introduce `MediaSourceProvider` interface + `MediaSourceRegistry`. Refactor existing VixSrc code into `VixSrcSourceProvider`. `DownloadQueueService` goes through the registry. No track-plan refactor yet — keep the language-driven sub-task plan working as-is.
3. Refactor `TrackDownloadOrchestrator` to be playlist-driven: add `resolveMaster` on the provider, build sub-tasks from parsed renditions, drop the per-language playlist re-resolution. Verify VixSrc downloads still match byte-for-byte (or close enough) on a known-good title.
4. Add the source tag to `SearchResultCard` + queue rows. Add the "include audio description" checkbox to the download dialog.
5. **Pre-step (manual, no commit):** capture the RaiPlay fixtures listed in §6.5. Don't start commit #5 until this is done — the parser is shaped by the real responses.
6. Implement `RaiPlaySourceProvider` + `RaiPlayApiClient` against the fixtures. Tests use the saved fixtures as canned HTTP responses.
7. Wire RaiPlay into the registry, `SearchView` fan-out, and the controller's `source` parameter.
8. Manual end-to-end verification with the COSMONAUTA and Hateful Eight examples (from an Italian network).
