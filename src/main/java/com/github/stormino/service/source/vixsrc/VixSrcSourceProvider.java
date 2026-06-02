package com.github.stormino.service.source.vixsrc;

import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ResolvedMedia;
import com.github.stormino.service.HlsParserService;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.VixSrcAvailabilityService;
import com.github.stormino.service.VixSrcExtractorService;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.service.VixSrcAvailabilityService;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * VixSrc-backed implementation of {@link MediaSourceProvider}. Delegates to
 * the existing TMDB/availability/extractor services — this class is a thin
 * adapter, not a re-implementation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VixSrcSourceProvider implements MediaSourceProvider {

    private static final String EPISODE_LIST_LANG = "it";

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "en", "it", "es", "fr", "de", "pt", "ja", "ko", "ru", "zh"
    );

    private final TmdbMetadataService tmdbService;
    private final VixSrcAvailabilityService availabilityService;
    private final VixSrcExtractorService extractorService;
    private final HlsParserService hlsParser;

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

        List<ContentMetadata> results = new ArrayList<>();
        if (filter.includesMovies()) {
            results.addAll(tmdbService.searchMovies(query));
        }
        if (filter.includesTv()) {
            results.addAll(tmdbService.searchTvShows(query));
        }
        return results;
    }

    @Override
    public AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages) {
        if (content.getTmdbId() == null) {
            return AvailabilityResult.builder().available(false).build();
        }

        // TV shows in the search results carry numberOfSeasons (not season/episode),
        // so we use that as the discriminator instead of season.
        boolean isTv = content.getNumberOfSeasons() != null;
        if (isTv) {
            return availabilityService.checkTvAvailability(content.getTmdbId(), languages);
        }
        return availabilityService.checkMovieAvailability(content.getTmdbId(), languages);
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
    public Set<String> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    @Override
    public List<EpisodeRef> listEpisodes(ContentMetadata show) {
        if (!tmdbService.isAvailable() || show.getTmdbId() == null) {
            log.debug("TMDB not configured or no tmdbId; cannot list episodes for '{}'", show.getTitle());
            return List.of();
        }

        // Fetch which episodes VixSrc actually has — TMDB may list more episodes than are
        // available on the source (e.g. future/unaired episodes).  If the endpoint returns
        // empty (error or truly empty show) we fall back to the full TMDB list so monitoring
        // still works rather than silently producing nothing.
        var available = availabilityService.fetchAvailableEpisodes(show.getTmdbId(), EPISODE_LIST_LANG);
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
            var episodes = tmdbService.getEpisodes(show.getTmdbId(), season.season_number);
            for (var ep : episodes) {
                if (filterByAvailability &&
                        !available.contains(new VixSrcAvailabilityService.EpisodeKey(season.season_number, ep.episode_number))) {
                    log.info("Skipping {}: S{}E{} not in VixSrc episode list",
                            show.getTitle(), season.season_number, ep.episode_number);
                    continue;
                }
                result.add(new EpisodeRef(
                        season.season_number,
                        ep.episode_number,
                        ep.name,
                        new VixSrcMetadata(show.getTmdbId(), season.season_number, ep.episode_number)
                ));
            }
        }
        log.info("listEpisodes for '{}': {} episode(s) returned (tmdbId={}, vixsrcAvailable={}, filtered={})",
                show.getTitle(), result.size(), show.getTmdbId(), available.size(), filterByAvailability);
        return result;
    }
}
