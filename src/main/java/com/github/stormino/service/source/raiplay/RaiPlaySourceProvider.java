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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
        "!'${raiplay.username:}'.isEmpty() && !'${raiplay.password:}'.isEmpty()")
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
        Optional<RaiPlaySearchResponse> response = apiClient.search(query, properties.getSearchPageSize());
        if (response.isEmpty() || response.get().agg() == null) {
            return List.of();
        }
        RaiPlaySearchResponse.Agg agg = response.get().agg();
        if (agg.video() == null || agg.video().cards() == null) {
            return List.of();
        }

        List<ContentMetadata> results = new ArrayList<>();
        for (RaiPlaySearchResult card : agg.video().cards()) {
            if (card.pathId() == null) continue;
            boolean isEpisode = card.isEpisode();
            if (filter == ContentTypeFilter.MOVIES && isEpisode) continue;
            if (filter == ContentTypeFilter.TV && !isEpisode) continue;

            ContentMetadata cm = toContentMetadata(card);
            if (cm != null) results.add(cm);
        }
        return results;
    }

    @Override
    public AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages) {
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
            log.warn("Missing video.content_url in descriptor for pathId={}", meta.pathId());
            return Optional.empty();
        }

        String referer = properties.getBaseUrl() + stripJsonSuffix(meta.pathId());

        return Optional.of(PlaylistInfo.builder()
                .url(Objects.requireNonNull(descriptor.contentUrl()))
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

    private ContentMetadata toContentMetadata(RaiPlaySearchResult card) {
        boolean isEpisode = card.isEpisode();
        String pathId = card.pathId().startsWith("/") ? card.pathId() : "/" + card.pathId();

        RaiPlayMetadata sourceMeta = new RaiPlayMetadata(
                pathId,
                card.id(),
                null,
                isEpisode ? card.stagione() : null,
                null
        );

        ContentMetadata.ContentMetadataBuilder builder = ContentMetadata.builder()
                .source(MediaSource.RAIPLAY)
                .sourceMetadata(sourceMeta)
                .overview(card.sommario());

        if (isEpisode) {
            // For episodes the show name is `programma`; the episode title is `titolo`.
            builder.title(card.programma() != null ? card.programma() : card.titolo());
            builder.episodeName(card.titolo());
            try { builder.season(Integer.parseInt(card.stagione())); } catch (NumberFormatException ignored) {}
            try { builder.episode(Integer.parseInt(card.episodio())); } catch (NumberFormatException ignored) {}
            // Mark as TV by setting numberOfSeasons to a non-null sentinel; real value
            // is unknown from a single search card.
            builder.numberOfSeasons(0);
        } else {
            builder.title(card.titolo());
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

    private static String stripJsonSuffix(String path) {
        return path.endsWith(".json") ? path.substring(0, path.length() - 5) : path;
    }
}
