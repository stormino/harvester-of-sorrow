# Changelog

What changed in each release of Harvester of Sorrow (HoS), newest first.
Versions follow [Semantic Versioning](https://semver.org): a major version can change how you use
HoS, a minor version adds features, a patch version only fixes things.

## 7.2.0 — 2026-11-20

### New
- **The download panel shows what a title really has.** Before you download, HoS reads the title's
  track list on VixSrc (for a show: its first episode) and offers only the audio languages, subtitles
  and qualities that exist. Italian is preselected when the title has it, otherwise the language it
  does have (e.g. English only for *Being Erica*).
- **Subtitles are chosen separately from audio.** The panel has its own subtitle row: your language
  and the audio languages first, the others behind "+N more".
- **New episodes wait for the Italian dub.** VixSrc often publishes a new episode in English a few days
  before the Italian audio. Monitoring now waits for Italian instead of downloading the English-only
  version, and downloads the episode anyway once it is 7 days old (`MONITORING_LANGUAGE_WAIT_DAYS`).
  Later episodes of the same show wait with it, so none is skipped.
- **Monitoring runs overnight**, at 03:00 and 06:00, instead of every hour (`MONITORING_CRON`).
  "Check now" in the library still checks a show at any time.

### Changed
- **New downloads are saved as `.mkv` instead of `.mp4`.** Only Matroska keeps track names and the
  "forced" flag, so players like Jellyfin now show every track properly:
  - audio and subtitles carry their language and a name ("Italian", "English", "Italian (Forced)");
  - only the Italian audio is the default track (before, every audio track was "default");
  - subtitles start switched off, except forced ones in the audio language (they only translate
    foreign-language lines and on-screen text);
  - audio built into the video (titles with a single language) is labelled too, instead of "not specified".

  Files already in the library stay as they are.
- The Settings page shows the monitoring schedule in words and the language wait.
- `MONITORING_INTERVAL_MS` is replaced by `MONITORING_CRON`.

### Fixed
- Quality "worst" downloaded the best quality.
- Subtitles in Spanish, German, French, Chinese, Japanese and other languages whose codes differ
  between standards ("es" vs "spa") were never downloaded when requested.
- Forced Italian subtitles were skipped on episodes where VixSrc labels them `forced-ita` instead of
  `ita-forced`.

## 7.1.0 — 2026-11-19

### Fixed
- Search found only titles with Italian audio on VixSrc. Titles VixSrc serves only in their original
  language (e.g. *Being Erica*) now appear too.

## 7.0.0 — 2026-11-17

### Changed
- **Unified search**: one search box for VixSrc, RaiPlay and XDCC, with results grouped per title.
  The separate XDCC page is gone.
- A title opens in a side panel (full screen on mobile), with one tab per source; the tabs stay
  visible even with long release lists.

## 6.0.0 — 2026-11-16

### New
- **XDCC downloads** over IRC: search through xdcc.eu (primary) and the PW Search Engine, results grouped
  by title with release quality and posters, and automatic fallback to another bot.

### Changed
- A friendly error page replaces raw stack traces.

## 5.4.0 — 2026-11-16

### New
- Archives downloaded from links (zip, rar, 7z, multi-part) are unpacked into the library.

### Fixed
- The Docker build retries Maven Central longer when rate limited.

## 5.3.1 — 2026-11-16

### Changed
- Upgraded to Spring Boot 4.1.

## 5.3.0 — 2026-11-16

### New
- **Download from a link**: Mega files and folders, and direct HTTP files.

### Changed
- Upgraded OkHttp to 5.5.1.

## 5.2.1 — 2026-11-15

### Fixed
- The library counts episodes in any video format, also outside season folders.

## 5.2.0 — 2026-11-15

### New
- The library hides shows that have ended, and scheduled checks skip them.

## 5.1.0 — 2026-11-15

### New
- Posters for RaiPlay search results.

## 5.0.1 — 2026-11-14

### Fixed
- Free disk space shows the media library's volume, not the temp folder's.

## 5.0.0 — 2026-11-14

### Changed
- **New web interface** (React) replacing the Vaadin one, with Search, Downloads, Library and
  Settings screens. See `docs/UPGRADING.md`.
- **REST API v1** under `/api/v1`, with live updates over Server-Sent Events and readable errors.
  The old `/api/...` endpoints are removed.
- One line per request in the log.

### Fixed
- Cancelled downloads stay cancelled and their temporary files are removed.
- VixSrc search shows only titles VixSrc can play.
- VixSrc downloads failing on the embed page.

## 4.2.0 — 2026-06-28

### New
- API documentation with Swagger UI.

## 4.1.0 — 2026-06-28

### New
- REST endpoints for the library and monitoring.

### Fixed
- VixSrc availability checks use the Italian catalogue consistently.

## 4.0.0 — 2026-06-21

### Changed
- **Renamed to Harvester of Sorrow (HoS)** (was VixSrc Downloader): new name, configuration
  namespace `hos` and theme.

## 3.0.1 — 2026-06-14

### Fixed
- Cancelling a download now stops its segment downloads.

## 3.0.0 — 2026-06-14

### New
- **TV library and episode monitoring**: HoS checks your shows and downloads new episodes, only those
  after the latest one you have. Monitoring stops if the show's folder is deleted.
- Title filter in the library.

### Fixed
- Episode lists only show episodes VixSrc really has.

## 2.1.0 — 2026-06-01

### New
- Choose several seasons and episodes at once when downloading a show.

## 2.0.2 — 2026-05-13

### Fixed
- Episode names in file names for single episodes and non-TMDB sources.

## 2.0.1 — 2026-05-11

### Changed
- Removed the "include audio description" option.

## 2.0.0 — 2026-05-11

### New
- **RaiPlay** as a second source: search, shows with all their seasons, `.srt` subtitles, login
  with your RaiPlay account.

### Changed
- Tracks to download are taken from the title's real track list.

### Fixed
- VixSrc availability checks.

## 1.2.1 — 2026-04-26

### Fixed
- Docker image issues.

## 1.2.0 — 2026-04-15

### Fixed
- Download fixes.

## 1.1.1 — 2026-04-14

### Changed
- Docker Compose update.

## 1.1.0 — 2026-04-14

### New
- The download queue is saved in a database (SQLite) and survives restarts.

## 1.0.3 — 2026-03-22

### Fixed
- `rsync` missing from the Docker image.

## 1.0.2 — 2026-03-21

### New
- Finished files are copied to the library with retries and checksum verification.

## 1.0.1 — 2026-03-21

### New
- The app shows its version.

## 1.0.0 — 2026-02-01

First release.
- Search movies and shows (TMDB) and download them from VixSrc, with audio and subtitle tracks.
- Whole shows in one go, separate folders for movies and shows.
- Fast downloads: segments in parallel, with retries.
- Download queue with progress, sizes and a "clear completed" button.
- Free disk space, Docker image published by GitHub Actions.
