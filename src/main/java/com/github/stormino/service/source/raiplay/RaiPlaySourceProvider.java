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
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    // ─── search ──────────────────────────────────────────────────────────────

    /**
     * Search uses {@code agg.titoli.cards[]} (program-level results), not
     * {@code agg.video.cards[]} (clips/individual videos).  Each program card
     * is classified as movie vs TV by querying its lightweight {@code info_url}
     * endpoint in parallel — a TV show advertises a {@code seasons} entry in
     * its details array, a movie does not.
     */
    @Override
    public List<ContentMetadata> search(String query, ContentTypeFilter filter) {
        Optional<RaiPlaySearchResponse> response = apiClient.search(query, properties.getSearchPageSize());
        if (response.isEmpty() || response.get().agg() == null) {
            return List.of();
        }
        RaiPlaySearchResponse.TitoliBucket titoli = response.get().agg().titoli();
        if (titoli == null || titoli.cards() == null || titoli.cards().isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<ContentMetadata>> futures = titoli.cards().stream()
                .filter(c -> c.pathId() != null)
                .map(card -> CompletableFuture.supplyAsync(() -> classifyAndBuild(card, filter)))
                .collect(Collectors.toList());

        return futures.stream()
                .map(f -> {
                    try { return f.get(); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ContentMetadata classifyAndBuild(RaiPlaySearchResponse.ProgramCard card,
                                             ContentTypeFilter filter) {
        Optional<RaiPlayProgramInfo> infoOpt = card.infoUrl() != null
                ? apiClient.getProgramInfo(card.infoUrl())
                : Optional.empty();

        boolean isTv = infoOpt.map(RaiPlayProgramInfo::isTvShow).orElse(false);

        if (filter == ContentTypeFilter.MOVIES && isTv) return null;
        if (filter == ContentTypeFilter.TV && !isTv) return null;

        // Fetch the program page to filter out non-film, non-episodic content
        // (documentaries, news programs, talk shows, etc.).
        Optional<RaiPlayProgramPage> pageOpt = card.pathId() != null
                ? apiClient.getProgramPage(card.pathId())
                : Optional.empty();
        if (pageOpt.isPresent()) {
            RaiPlayProgramPage page = pageOpt.get();
            if (!page.isMovie() && page.episodesBlock().isEmpty()) {
                return null; // Not a film and not an episodic series
            }
            // Use program page as the authoritative movie/TV discriminator.
            isTv = !page.isMovie();
        }

        // Re-apply content-type filter using the authoritative classification.
        if (filter == ContentTypeFilter.MOVIES && isTv) return null;
        if (filter == ContentTypeFilter.TV && !isTv) return null;

        RaiPlayMetadata sourceMeta = new RaiPlayMetadata(card.pathId(), card.id(), null, null, null);

        ContentMetadata.ContentMetadataBuilder builder = ContentMetadata.builder()
                .source(MediaSource.RAIPLAY)
                .sourceMetadata(sourceMeta)
                .title(card.titolo())
                .year(infoOpt.flatMap(RaiPlayProgramInfo::extractYear).orElse(null))
                .overview(infoOpt.map(RaiPlayProgramInfo::description).orElse(null));

        if (isTv) {
            int seasons = infoOpt.flatMap(RaiPlayProgramInfo::seasonCount).orElse(0);
            builder.numberOfSeasons(seasons);
            pageOpt.map(RaiPlaySourceProvider::totalEpisodes)
                   .filter(n -> n > 0)
                   .ifPresent(builder::totalEpisodes);
        }
        return builder.build();
    }

    /**
     * Sums {@code episode_size.number} across all season sets in the episodes
     * block. The program page reports this per season ("12 episodi"), so
     * totalling them gives the show-wide episode count.
     */
    private static int totalEpisodes(RaiPlayProgramPage page) {
        return page.episodesBlock()
                .map(b -> b.sets() == null ? 0 : b.sets().stream()
                        .map(RaiPlayProgramPage.ContentSet::episodeSize)
                        .filter(Objects::nonNull)
                        .map(RaiPlayProgramPage.EpisodeSize::number)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum())
                .orElse(0);
    }

    @Override
    public AvailabilityResult checkAvailability(ContentMetadata content, Set<String> languages) {
        return AvailabilityResult.builder()
                .available(true)
                .availableLanguages(SUPPORTED_LANGUAGES)
                .build();
    }

    // ─── playlist resolution ─────────────────────────────────────────────────

    /**
     * Resolves the HLS playlist for a download task.  The metadata's pathId may
     * be either:
     * <ul>
     *   <li>A direct video descriptor ({@code /video/...json}) — e.g. items produced
     *       by {@link #listEpisodes(ContentMetadata)}.  Existing flow.</li>
     *   <li>A program page ({@code /programmi/{slug}.json}) — produced by
     *       {@link #search(String, ContentTypeFilter)}.  Resolves to either
     *       {@code first_item_path} (movie) or the appropriate episode's pathId
     *       (TV) before falling through to the descriptor fetch.</li>
     * </ul>
     */
    @Override
    public Optional<PlaylistInfo> getPlaylist(DownloadTask task, String language) {
        RaiPlayMetadata meta = extractMeta(task);
        if (meta == null || meta.pathId() == null) return Optional.empty();

        String videoPathId = meta.pathId().startsWith("/video/")
                ? meta.pathId()
                : resolveVideoPathId(meta.pathId(), task).orElse(null);

        if (videoPathId == null) {
            log.warn("Could not resolve video pathId for task {} (program pathId={})",
                    task.getId(), meta.pathId());
            return Optional.empty();
        }

        Optional<RaiPlayContentDescriptor> descriptorOpt = apiClient.getContentDescriptor(videoPathId);
        if (descriptorOpt.isEmpty()) {
            log.warn("No content descriptor for pathId={}", videoPathId);
            return Optional.empty();
        }
        RaiPlayContentDescriptor descriptor = descriptorOpt.get();
        if (descriptor.contentUrl() == null) {
            log.warn("Missing video.content_url in descriptor for pathId={}", videoPathId);
            return Optional.empty();
        }

        String referer = properties.getBaseUrl() + stripJsonSuffix(videoPathId);
        return Optional.of(PlaylistInfo.builder()
                .url(descriptor.contentUrl())
                .language("it")
                .referer(referer)
                .verified(false)
                .build());
    }

    private Optional<String> resolveVideoPathId(String programPathId, DownloadTask task) {
        Optional<RaiPlayProgramPage> pageOpt = apiClient.getProgramPage(programPathId);
        if (pageOpt.isEmpty()) return Optional.empty();
        RaiPlayProgramPage page = pageOpt.get();

        if (page.isMovie()) {
            return Optional.ofNullable(page.firstItemPath());
        }
        return resolveEpisodePathId(page, task.getSeason(), task.getEpisode());
    }

    private Optional<String> resolveEpisodePathId(RaiPlayProgramPage page,
                                                  Integer season, Integer episode) {
        if (season == null || episode == null) return Optional.empty();

        Optional<RaiPlayProgramPage.Block> blockOpt = page.episodesBlock();
        if (blockOpt.isEmpty() || blockOpt.get().sets() == null) return Optional.empty();
        RaiPlayProgramPage.Block block = blockOpt.get();

        // Sets are ordered by season ("Stagione 1", "Stagione 2", ...). Index by season-1.
        if (season < 1 || season > block.sets().size()) return Optional.empty();
        RaiPlayProgramPage.ContentSet seasonSet = block.sets().get(season - 1);

        String slug = extractSlug(page.pathId());
        if (slug == null) return Optional.empty();

        Optional<RaiPlayEpisodesPage> episodesOpt =
                apiClient.getSeasonEpisodes(slug, block.id(), seasonSet.id());
        if (episodesOpt.isEmpty()) return Optional.empty();

        Optional<RaiPlayEpisodesPage.SeasonContentSet> contentSetOpt =
                episodesOpt.get().episodiSeason(seasonSet.id());
        if (contentSetOpt.isEmpty() || contentSetOpt.get().cards() == null) return Optional.empty();

        return contentSetOpt.get().cards().stream()
                .filter(c -> Objects.equals(c.parseEpisode(), episode))
                .map(RaiPlayEpisodesPage.EpisodeCard::pathId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Override
    public Optional<ResolvedMedia> resolveMaster(DownloadTask task, String primaryLanguage) {
        RaiPlayMetadata meta = extractMeta(task);
        if (meta == null || meta.pathId() == null) return Optional.empty();

        String videoPathId = meta.pathId().startsWith("/video/")
                ? meta.pathId()
                : resolveVideoPathId(meta.pathId(), task).orElse(null);
        if (videoPathId == null) return Optional.empty();

        Optional<RaiPlayContentDescriptor> descriptorOpt = apiClient.getContentDescriptor(videoPathId);
        if (descriptorOpt.isEmpty() || descriptorOpt.get().contentUrl() == null) {
            return Optional.empty();
        }
        RaiPlayContentDescriptor descriptor = descriptorOpt.get();

        String referer = properties.getBaseUrl() + stripJsonSuffix(videoPathId);
        PlaylistInfo playlist = PlaylistInfo.builder()
                .url(descriptor.contentUrl())
                .language("it")
                .referer(referer)
                .verified(false)
                .build();

        Optional<HlsParserService.HlsPlaylist> parsedOpt =
                hlsParser.parsePlaylist(playlist.getUrl(), playlist.getReferer());
        if (parsedOpt.isEmpty()) return Optional.empty();

        HlsParserService.HlsPlaylist parsed = parsedOpt.get();
        mergeDescriptorSubtitles(parsed, descriptor);
        return Optional.of(new ResolvedMedia(playlist, parsed));
    }

    /**
     * RaiPlay does not embed subtitle renditions in its HLS masters. The
     * content descriptor exposes them in {@code video.subtitlesArray} as direct
     * .srt URLs (relative paths under {@code raiplay.it}). Merge them into the
     * parsed master so the orchestrator picks them up as regular subtitle
     * sub-tasks; {@link com.github.stormino.service.SubtitleTrackDownloadStrategy}
     * handles direct-file URLs alongside HLS ones.
     */
    private void mergeDescriptorSubtitles(HlsParserService.HlsPlaylist parsed,
                                          RaiPlayContentDescriptor descriptor) {
        if (descriptor.video() == null || descriptor.video().subtitles() == null
                || descriptor.video().subtitles().isEmpty()) {
            return;
        }
        List<HlsParserService.SubtitleTrack> merged = new ArrayList<>(
                parsed.getSubtitleTracks() != null ? parsed.getSubtitleTracks() : List.of());
        for (RaiPlayContentDescriptor.Subtitle sub : descriptor.video().subtitles()) {
            if (sub.url() == null || sub.url().isBlank()) continue;
            merged.add(HlsParserService.SubtitleTrack.builder()
                    .url(absoluteSubtitleUrl(sub.url()))
                    .language(sub.language())
                    .name(sub.label() != null ? sub.label()
                            : "Subtitle - " + (sub.language() != null ? sub.language() : "und"))
                    .build());
        }
        parsed.setSubtitleTracks(merged);
    }

    /**
     * Subtitle URLs come back as absolute (rare) or as site-relative paths with
     * unencoded spaces — e.g. {@code /dl/video/stl/PISTA NERA.srt}. Encode each
     * segment so the eventual HTTP fetch succeeds.
     */
    private String absoluteSubtitleUrl(String urlOrPath) {
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            return urlOrPath;
        }
        String[] parts = urlOrPath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return properties.getBaseUrl() + sb;
    }

    // ─── episode enumeration ─────────────────────────────────────────────────

    /**
     * Enumerates every episode of a TV show by traversing the program page →
     * episodes blocks → per-season {@code episodes.json}.  Each returned
     * {@link EpisodeRef} carries the video descriptor pathId in its
     * {@link RaiPlayMetadata}, ready for {@link #getPlaylist}.
     *
     * <p>Returns empty for movies (callers should fall back to a single-task
     * download using the show's own metadata).
     */
    @Override
    public List<EpisodeRef> listEpisodes(ContentMetadata show) {
        if (!(show.getSourceMetadata() instanceof RaiPlayMetadata meta) || meta.pathId() == null) {
            return List.of();
        }
        Optional<RaiPlayProgramPage> pageOpt = apiClient.getProgramPage(meta.pathId());
        if (pageOpt.isEmpty()) return List.of();
        RaiPlayProgramPage page = pageOpt.get();
        if (page.isMovie()) return List.of();

        Optional<RaiPlayProgramPage.Block> blockOpt = page.episodesBlock();
        if (blockOpt.isEmpty() || blockOpt.get().sets() == null) return List.of();
        RaiPlayProgramPage.Block block = blockOpt.get();

        String slug = extractSlug(page.pathId());
        if (slug == null) return List.of();

        // Fetch all seasons in parallel.
        List<CompletableFuture<List<EpisodeRef>>> futures = block.sets().stream()
                .map(set -> CompletableFuture.supplyAsync(() ->
                        episodesForSeason(slug, block.id(), set.id())))
                .collect(Collectors.toList());

        List<EpisodeRef> all = new ArrayList<>();
        for (CompletableFuture<List<EpisodeRef>> f : futures) {
            try { all.addAll(f.get()); }
            catch (Exception e) { log.warn("Season fetch failed for show {}: {}", slug, e.getMessage()); }
        }
        return all;
    }

    private List<EpisodeRef> episodesForSeason(String slug, String blockId, String setId) {
        return apiClient.getSeasonEpisodes(slug, blockId, setId)
                .flatMap(p -> p.episodiSeason(setId))
                .map(cs -> cs.cards() == null ? Stream.<RaiPlayEpisodesPage.EpisodeCard>empty() : cs.cards().stream())
                .orElseGet(Stream::empty)
                .filter(c -> c.parseSeason() != null && c.parseEpisode() != null && c.pathId() != null)
                .map(c -> new EpisodeRef(
                        c.parseSeason(),
                        c.parseEpisode(),
                        c.episodeTitle(),
                        new RaiPlayMetadata(c.pathId(), c.id(), null, c.season(), c.episode())))
                .collect(Collectors.toList());
    }

    @Override
    public Set<String> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

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

    /** Extracts {@code "roccoschiavone"} from {@code /programmi/roccoschiavone.json}. */
    private static String extractSlug(String programPathId) {
        if (programPathId == null) return null;
        String stripped = stripJsonSuffix(programPathId);
        int slash = stripped.lastIndexOf('/');
        return (slash >= 0 && slash < stripped.length() - 1) ? stripped.substring(slash + 1) : null;
    }
}
