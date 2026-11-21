package com.github.stormino.service.source.vixsrc;

import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaInfo;
import com.github.stormino.model.MediaSource;
import com.github.stormino.util.LanguageCodes;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ResolvedMedia;
import com.github.stormino.service.HlsParserService;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.VixSrcAvailabilityService;
import com.github.stormino.service.VixSrcExtractorService;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VixSrc-backed implementation of {@link MediaSourceProvider}. Delegates to
 * the existing TMDB/availability/extractor services — this class is a thin
 * adapter, not a re-implementation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VixSrcSourceProvider implements MediaSourceProvider {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "en", "it", "es", "fr", "de", "pt", "ja", "ko", "ru", "zh"
    );

    private final TmdbMetadataService tmdbService;
    private final AppProperties properties;
    private final VixSrcAvailabilityService availabilityService;
    private final VixSrcExtractorService extractorService;
    private final HlsParserService hlsParser;

    /** Availability checks may hit the network (one probe per title off the catalogue list). */
    private final ExecutorService availabilityExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "vixsrc-availability");
        t.setDaemon(true);
        return t;
    });

    @Override
    public MediaSource source() {
        return MediaSource.VIXSRC;
    }

    @Override
    public List<ContentMetadata> search(String query, ContentTypeFilter filter) {
        if (!tmdbService.isAvailable()) {
            log.debug("TMDB not configured; VixSrc search returns empty");
            return List.of();
        }

        // Titles in the download language ("Curioso come George"), looked up while the English search runs
        String language = properties.getDownload().getDefaultLanguage();
        boolean localize = language != null && !language.isBlank() && !language.equalsIgnoreCase("en");
        CompletableFuture<Map<Integer, String>> localMovies = localize && filter.includesMovies()
                ? CompletableFuture.supplyAsync(() -> tmdbService.localTitles(query, false, language), availabilityExecutor)
                : CompletableFuture.completedFuture(Map.of());
        CompletableFuture<Map<Integer, String>> localShows = localize && filter.includesTv()
                ? CompletableFuture.supplyAsync(() -> tmdbService.localTitles(query, true, language), availabilityExecutor)
                : CompletableFuture.completedFuture(Map.of());

        List<ContentMetadata> candidates = new ArrayList<>();
        if (filter.includesMovies()) {
            candidates.addAll(tmdbService.searchMovies(query));
        }
        if (filter.includesTv()) {
            candidates.addAll(tmdbService.searchTvShows(query));
        }
        for (ContentMetadata c : candidates) {
            Map<Integer, String> local = (c.getContentType() == DownloadTask.ContentType.TV ? localShows : localMovies).join();
            String t = c.getTmdbId() == null ? null : local.get(c.getTmdbId());
            if (t != null && !t.isBlank() && !t.equalsIgnoreCase(c.getTitle())) {
                c.setLocalTitle(t);
            }
        }

        // Titles missing from the catalogue list cost one HTTP probe each, so check
        // the candidates concurrently; join() keeps TMDB's ranking order.
        Set<String> languages = Set.of(VixSrcAvailabilityService.EPISODE_LIST_LANG);
        List<CompletableFuture<Boolean>> checks = candidates.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    AvailabilityResult avail = checkAvailability(c, languages);
                    if (!avail.isAvailable()) {
                        log.debug("Filtered out '{}' (tmdbId={}) — not available on VixSrc",
                                c.getTitle(), c.getTmdbId());
                    }
                    return avail.isAvailable();
                }, availabilityExecutor))
                .toList();
        List<ContentMetadata> available = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (checks.get(i).join()) {
                available.add(candidates.get(i));
            }
        }
        return available;
    }

    @Override
    public AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages) {
        if (content.getTmdbId() == null) {
            return AvailabilityResult.builder().available(false).build();
        }

        // TV shows in the search results carry numberOfSeasons (not season/episode),
        // so we use that as the discriminator instead of season.
        // VixSrc catalogue list endpoint is only reliable for "it" language
        Set<String> vixSrcLanguages = Set.of(VixSrcAvailabilityService.EPISODE_LIST_LANG);
        boolean isTv = content.getContentType() != null
                ? content.getContentType() == DownloadTask.ContentType.TV
                : content.getNumberOfSeasons() != null;
        if (isTv) {
            return availabilityService.checkTvAvailability(content.getTmdbId(), vixSrcLanguages);
        }
        return availabilityService.checkMovieAvailability(content.getTmdbId(), vixSrcLanguages);
    }

    @Override
    public Optional<PlaylistInfo> getPlaylist(DownloadTask task, String language) {
        if (task.getContentType() == DownloadTask.ContentType.TV) {
            return extractorService.getTvPlaylist(
                    task.getTmdbId(), task.getSeason(), task.getEpisode(), language);
        }
        return extractorService.getMoviePlaylist(task.getTmdbId(), language);
    }

    @Override
    public Optional<ResolvedMedia> resolveMaster(DownloadTask task, String primaryLanguage) {
        Optional<PlaylistInfo> playlistInfoOpt = getPlaylist(task, primaryLanguage);
        if (playlistInfoOpt.isEmpty()) {
            return Optional.empty();
        }
        PlaylistInfo playlistInfo = playlistInfoOpt.get();
        return hlsParser.parsePlaylist(playlistInfo.getUrl(), playlistInfo.getReferer())
                .map(parsed -> new ResolvedMedia(playlistInfo, parsed));
    }

    @Override
    public Optional<MediaInfo> mediaInfo(ContentMetadata content, Integer season, Integer episode) {
        if (content.getTmdbId() == null) {
            return Optional.empty();
        }
        boolean tv = content.getContentType() == DownloadTask.ContentType.TV;
        DownloadTask probe = DownloadTask.builder()
                .source(MediaSource.VIXSRC)
                .contentType(tv ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE)
                .tmdbId(content.getTmdbId())
                .build();
        if (tv) {
            if (season == null || episode == null) {
                // First regular episode VixSrc has (specials, season 0, last); S1E1 when the
                // catalogue doesn't list the show
                var first = availabilityService.fetchAvailableEpisodes(content.getTmdbId(),
                                VixSrcAvailabilityService.EPISODE_LIST_LANG).stream()
                        .min(java.util.Comparator.<VixSrcAvailabilityService.EpisodeKey>comparingInt(k -> k.season() == 0 ? 1 : 0)
                                .thenComparingInt(VixSrcAvailabilityService.EpisodeKey::season)
                                .thenComparingInt(VixSrcAvailabilityService.EpisodeKey::episode));
                season = first.map(VixSrcAvailabilityService.EpisodeKey::season).orElse(1);
                episode = first.map(VixSrcAvailabilityService.EpisodeKey::episode).orElse(1);
            }
            probe.setSeason(season);
            probe.setEpisode(episode);
        }
        Optional<ResolvedMedia> resolved = resolveMaster(probe, VixSrcAvailabilityService.EPISODE_LIST_LANG);
        if (resolved.isEmpty() || resolved.get().parsed().getType() != HlsParserService.PlaylistType.MASTER) {
            log.debug("No master playlist for tmdbId={} S{}E{}", content.getTmdbId(), season, episode);
            return Optional.empty();
        }
        var parsed = resolved.get().parsed();

        List<MediaInfo.Track> audio = new ArrayList<>();
        if (parsed.getAudioTracks() == null || parsed.getAudioTracks().isEmpty()) {
            String lang = embeddedAudioLanguage(probe).orElse(null);
            audio.add(new MediaInfo.Track(lang, lang != null ? LanguageCodes.displayName(lang) : "Original audio", false));
        } else {
            for (var t : parsed.getAudioTracks()) {
                String lang = LanguageCodes.toIso1(t.getLanguage()).orElse(null);
                if (audio.stream().noneMatch(a -> java.util.Objects.equals(a.language(), lang))) {
                    audio.add(new MediaInfo.Track(lang, lang != null ? LanguageCodes.displayName(lang) : t.getName(), false));
                }
            }
        }
        List<MediaInfo.Track> subtitles = new ArrayList<>();
        if (parsed.getSubtitleTracks() != null) {
            for (var t : parsed.getSubtitleTracks()) {
                String lang = LanguageCodes.toIso1(t.getLanguage()).orElse(null);
                subtitles.add(new MediaInfo.Track(lang, t.getName(), t.isForced()));
            }
        }
        List<Integer> heights = parsed.getVideoVariants() == null ? List.of() : parsed.getVideoVariants().stream()
                .map(v -> v.getResolution() == null ? null : v.getResolution().replaceAll("^\\d+x", ""))
                .filter(h -> h != null && h.matches("\\d+"))
                .map(Integer::parseInt)
                .distinct()
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
        return Optional.of(new MediaInfo(audio, subtitles, heights));
    }

    /** VixSrc doesn't say; the title's original language (TMDB) is what such streams carry. */
    @Override
    public Optional<String> embeddedAudioLanguage(DownloadTask task) {
        if (task.getTmdbId() == null) {
            return Optional.empty();
        }
        return tmdbService.getOriginalLanguage(task.getTmdbId(), task.getContentType() == DownloadTask.ContentType.TV);
    }

    @Override
    public Set<String> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    @Override
    public List<EpisodeRef> listEpisodes(ContentMetadata show) {
        return listEpisodes(show, null);
    }

    @Override
    public List<EpisodeRef> listEpisodes(ContentMetadata show, Integer onlySeason) {
        if (!tmdbService.isAvailable() || show.getTmdbId() == null) {
            log.debug("TMDB not configured or no tmdbId; cannot list episodes for '{}'", show.getTitle());
            return List.of();
        }

        // Fetch which episodes VixSrc actually has — TMDB may list more episodes than are
        // available on the source (e.g. future/unaired episodes).  If the endpoint returns
        // empty (error or truly empty show) we fall back to the full TMDB list so monitoring
        // still works rather than silently producing nothing.
        var available = availabilityService.fetchAvailableEpisodes(show.getTmdbId(), VixSrcAvailabilityService.EPISODE_LIST_LANG);
        if (available.isEmpty()) {
            log.warn("VixSrc episode availability list returned empty for '{}' (tmdbId={}); falling back to full TMDB list",
                    show.getTitle(), show.getTmdbId());
        } else {
            log.info("VixSrc episode availability for '{}' (tmdbId={}): {} episode(s) available",
                    show.getTitle(), show.getTmdbId(), available.size());
        }
        boolean filterByAvailability = !available.isEmpty();

        List<EpisodeRef> result = new ArrayList<>();
        var seasons = tmdbService.getSeasons(show.getTmdbId());
        for (var season : seasons) {
            if (onlySeason != null && !onlySeason.equals(season.season_number)) {
                continue;
            }
            var episodes = tmdbService.getEpisodes(show.getTmdbId(), season.season_number);
            for (var ep : episodes) {
                if (filterByAvailability &&
                        !available.contains(new VixSrcAvailabilityService.EpisodeKey(season.season_number, ep.episode_number))) {
                    log.debug("Skipping {}: S{}E{} not in VixSrc episode list",
                            show.getTitle(), season.season_number, ep.episode_number);
                    continue;
                }
                result.add(new EpisodeRef(
                        season.season_number,
                        ep.episode_number,
                        ep.name,
                        new VixSrcMetadata(show.getTmdbId(), season.season_number, ep.episode_number),
                        ep.air_date == null ? null
                                : ep.air_date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                ));
            }
        }
        log.info("listEpisodes for '{}': {} episode(s) returned (tmdbId={}, vixsrcAvailable={}, filtered={})",
                show.getTitle(), result.size(), show.getTmdbId(), available.size(), filterByAvailability);
        return result;
    }
}
