# Changelog

What changed in each release of Harvester of Sorrow (HoS), newest first.
Versions follow [Semantic Versioning](https://semver.org): a major version can change how you use
HoS, a minor version adds features, a patch version only fixes things.

## 7.7.0 — 2026-11-28

### Fixed
- **Registering the IRC nick works on SceneP2P.** Its NickServ only registers a nick that has been in use
  for 30 seconds, and HoS asked right after switching to it, so the download failed with "You must have
  been using this nick for at least 30 seconds to register". HoS now waits as long as NickServ asks
  (up to 2 minutes) and registers again, then joins the channel.

## 7.6.0 — 2026-11-28

### New
- **XDCC from channels that need a registered nick.** Some channels, such as #THE.SOURCE on SceneP2P,
  only let registered users in, so the download stopped at "You need a registered nick to join that
  channel". The new **Settings › IRC Account** section takes a nick and password. HoS stays anonymous
  as before, and only when a channel asks for a registered nick does it switch to yours, log in and
  join again, without dropping other downloads on that network. It remembers those networks and logs in
  there straight away next time.
- **HoS can register the nick for you.** Turn on "Register the nick automatically" and give an e-mail.
  If a network then mails a code to confirm it, the network shows "waiting for the e-mail code" in
  Settings with a box to paste it.
- Settings lists each network with the nick in use and whether it is logged in. When the channel still
  refuses, the download's error says why (wrong password, nick not registered, code not confirmed yet).

## 7.5.1 — 2026-11-26

### Fixed
- **VixSrc downloads work again.** VixSrc now refuses the Full HD playlist unless the video page allows
  it, and HoS always asked for Full HD, so every download failed at once ("Video track download
  failed"). HoS now asks for Full HD only when VixSrc offers it.

## 7.5.0 — 2026-11-24

### New
- **Films not out yet can be saved to the watchlist.** A title still in cinemas, or just out but not on
  VixSrc yet, used to be missing from the search: *Se domani non torno* found nothing at all. Now it is
  shown with "Not available yet" (or "Out 15 Dec 2026" before its release date) and can be saved.
  Only recent and upcoming titles are shown this way.
- **The watchlist tells you when a saved title can be downloaded.** Every morning at 07:00 HoS looks for
  the saved titles nothing could download yet, on VixSrc, RaiPlay and XDCC (by each of the title's names).
  When one turns up it gets an **Available for download** badge, and the Watchlist tab shows how many
  in green. "Find it" opens the search on the title, with the search box filled in. "Check N waiting
  now" runs the check at any time. Set the time with `WATCHLIST_CHECK_CRON`.

### Fixed
- **RaiPlay results match the search.** RaiPlay's own search is loose: "Se domani non torno" returned 27
  titles such as *A domani* and *Il Grande Torino*. Now only titles with every word of the search are
  kept (the last word can be cut short, so "montalb" still finds *Il commissario Montalbano*), and
  searches are faster since the others are no longer looked up.
- **XDCC search by a title's other name works with punctuation.** xdcc.eu finds nothing for
  "Dune - Parte due" or "Dune: Part Two", so the search by the Italian or English name (7.4.0) often came
  back empty. Punctuation and accents are now left out of XDCC searches.

## 7.4.1 — 2026-11-21

### Changed
- **A title's Italian name is shown under its title**, on the result card and in the title panel
  (e.g. *Curious George* / Curioso come George), instead of as an extra entry in the status row.
  The xdcc.eu count includes the packs found by the other names.
- **The PW Search Engine is off and no longer listed.** Its server still refuses HoS; set
  `XDCC_PW_ENABLED=true` to turn it back on once access is enabled.

## 7.4.0 — 2026-11-21

### New
- **XDCC is searched by a title's Italian and English names.** XDCC files are named in the language
  of the release, so "Curioso come George" only found the Italian files and "Curious George" only the
  English ones. Now the search also looks XDCC up by the other name of the first results (up to two
  extra names) and merges what it finds: either name finds both. The extra names appear in the status
  row under the search box, e.g. ✓ "Curious George" 15.
- Search results match a title's Italian name too, so an Italian search ranks the right titles first.

## 7.3.2 — 2026-11-21

### Fixed
- **XDCC no longer mixes a film with a show of the same name.** *Curious George* (2006) is both a film
  and a TV show: the film's releases were listed under the show because two loose episodes were on
  XDCC too. Now the film's releases go to the film and only the episodes (and season packs) to the show.
- **When XDCC has only a few episodes of a show, the panel says so**, e.g. "XDCC has only 2 single
  episodes of this show (S15E11, S15E13). The whole show is on VixSrc."
- **Games, software, music and books are left out of XDCC results** (e.g. the *Curious George* game,
  `REPACK-RELOADED.tar`).
- XDCC release names no longer start with the bot's network (`[ irc.coreirc.net ] …`).

## 7.3.1 — 2026-11-21

### Fixed
- **Search results stay when you switch pages.** Going to Downloads, Watchlist, Library or Settings and
  back to Search shows your last search again (with its Films/Shows filter) instead of an empty page.
  Results are kept for 30 minutes, so they appear instantly; press Search to look them up again.
  The HoS logo still opens an empty search.

## 7.3.0 — 2026-11-20

### New
- **Watchlist**: save titles for later and decide calmly whether to watch them.
  - A bookmark on every search result saves the title (tap it again to remove it); a toast offers Undo.
  - The new **Watchlist** page (in the menu, with a count) shows each saved title with its poster,
    plot, rating and when you saved it, filterable by films and shows.
  - Clicking a title, or "Download…", opens the usual title panel. It looks the title up again, so it
    shows where you can download it *now* (VixSrc, RaiPlay, XDCC), even if that changed since you saved it.
  - Each person has their own watchlist (from the Authelia login).
- **Watch trailer**: the title panel and the watchlist link to the title's trailer on YouTube (from
  TMDB), in Italian when there is one.

### Fixed
- On narrow screens the header shows "HoS" instead of hiding or squeezing the full name.

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
