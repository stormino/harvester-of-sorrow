package com.github.stormino.service.source.vixsrc;

import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.VixSrcAvailabilityService;
import com.github.stormino.service.VixSrcExtractorService;
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

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "en", "it", "es", "fr", "de", "pt", "ja", "ko", "ru", "zh"
    );

    private final TmdbMetadataService tmdbService;
    private final VixSrcAvailabilityService availabilityService;
    private final VixSrcExtractorService extractorService;

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
    public Set<String> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }
}
