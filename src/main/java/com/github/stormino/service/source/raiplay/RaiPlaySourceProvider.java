package com.github.stormino.service.source.raiplay;

import com.github.stormino.config.RaiPlayProperties;
import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ResolvedMedia;
import com.github.stormino.model.source.RaiPlayMetadata;
import com.github.stormino.service.HlsParserService;
import com.github.stormino.service.source.MediaSourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RaiPlaySourceProvider implements MediaSourceProvider {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("it");

    private final RaiPlayApiClient apiClient;
    private final HlsParserService hlsParser;
    private final RaiPlayProperties properties;

    @Override
    public MediaSource source() {
        return MediaSource.RAIPLAY;
    }

    @Override
    public List<ContentMetadata> search(String query, ContentTypeFilter filter) {
        return apiClient.search(query, properties.getSearchPageSize())
                .map(response -> response.results().stream()
                        .filter(r -> r.pathId() != null)
                        .filter(r -> matchesFilter(r, filter))
                        .map(this::toContentMetadata)
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages) {
        // All content returned from the RaiPlay catalogue is considered available.
        return AvailabilityResult.builder()
                .available(true)
                .availableLanguages(SUPPORTED_LANGUAGES)
                .build();
    }

    @Override
    public Optional<PlaylistInfo> getPlaylist(DownloadTask task, String language) {
        RaiPlayMetadata meta = extractMeta(task);
        if (meta == null || meta.pathId() == null) return Optional.empty();

        Optional<RaiPlayContentDescriptor> descriptorOpt = apiClient.getContentDescriptor(meta.pathId());
        if (descriptorOpt.isEmpty()) {
            log.warn("No content descriptor for pathId={}", meta.pathId());
            return Optional.empty();
        }

        RaiPlayContentDescriptor descriptor = descriptorOpt.get();
        if (descriptor.contentUrl() == null) {
            log.warn("Missing content_url in descriptor for pathId={}", meta.pathId());
            return Optional.empty();
        }

        String hlsUrl = apiClient.buildHlsUrl(descriptor.contentUrl());
        String referer = properties.getBaseUrl() + "/" + meta.pathId();

        return Optional.of(PlaylistInfo.builder()
                .url(hlsUrl)
                .language("it")
                .referer(referer)
                .verified(false)
                .build());
    }

    @Override
    public Optional<ResolvedMedia> resolveMaster(DownloadTask task, String primaryLanguage) {
        return getPlaylist(task, primaryLanguage)
                .flatMap(playlist -> hlsParser.parsePlaylist(playlist.getUrl(), playlist.getReferer())
                        .map(parsed -> new ResolvedMedia(playlist, parsed)));
    }

    @Override
    public Set<String> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    private boolean matchesFilter(RaiPlaySearchResult result, ContentTypeFilter filter) {
        boolean isFilm = isFilmType(result.type());
        return switch (filter) {
            case MOVIES -> isFilm;
            case TV -> !isFilm;
            case BOTH -> true;
        };
    }

    /**
     * RaiPlay type names observed: "Film", "Film tv" → movies; everything else → TV.
     * Adjust once real search fixtures are captured.
     */
    private boolean isFilmType(String type) {
        if (type == null) return true;
        return type.toLowerCase().contains("film");
    }

    private ContentMetadata toContentMetadata(RaiPlaySearchResult result) {
        RaiPlayMetadata sourceMeta = new RaiPlayMetadata(
                result.pathId(),
                result.uid(),
                result.programInfo() != null ? result.programInfo().uid() : null,
                null,
                null
        );

        ContentMetadata.ContentMetadataBuilder builder = ContentMetadata.builder()
                .source(MediaSource.RAIPLAY)
                .sourceMetadata(sourceMeta)
                .title(result.name())
                .overview(result.description());

        if (result.datePublished() != null && result.datePublished().length() >= 4) {
            try {
                builder.year(Integer.parseInt(result.datePublished().substring(0, 4)));
            } catch (NumberFormatException ignored) {}
        }

        if (!isFilmType(result.type())) {
            // Mark as TV by setting numberOfSeasons to a non-null sentinel so
            // SearchResultCard treats it as TV content; real value unknown from search.
            builder.numberOfSeasons(0);
        }

        return builder.build();
    }

    private RaiPlayMetadata extractMeta(DownloadTask task) {
        if (task.getSourceMetadata() instanceof RaiPlayMetadata meta) {
            return meta;
        }
        log.warn("Task {} has no RaiPlayMetadata (source={})", task.getId(), task.getSource());
        return null;
    }
}
