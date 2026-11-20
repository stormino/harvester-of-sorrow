package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.SourceRefCodec;
import com.github.stormino.api.dto.EpisodeDto;
import com.github.stormino.api.dto.EpisodesRequest;
import com.github.stormino.api.dto.MediaInfoDto;
import com.github.stormino.api.dto.MediaInfoRequest;
import com.github.stormino.api.dto.TrailerDto;
import com.github.stormino.api.dto.SearchResultDto;
import com.github.stormino.api.dto.SeasonDto;
import com.github.stormino.api.dto.SourceDto;
import com.github.stormino.api.dto.SourcesDto;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaInfo;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.RaiPlayMetadata;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.source.EpisodeRef;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Content", description = "Search, sources and episode listings")
public class ContentController {

    static final List<String> QUALITIES = List.of("best", "1080", "720", "worst");

    /** best, worst, or a video height such as 1080, 720, 480 (what a title's media info offers). */
    static boolean isValidQuality(String quality) {
        return QUALITIES.contains(quality) || quality.matches("[1-9][0-9]{2,3}");
    }

    private final MediaSourceRegistry sourceRegistry;
    private final SourceRefCodec sourceRefCodec;
    private final AppProperties properties;
    private final TmdbMetadataService tmdbService;

    @Operation(summary = "Search movies and TV shows",
            description = "Searches every enabled source in parallel. VixSrc results are filtered by real availability.")
    @GetMapping("/search")
    public List<SearchResultDto> search(
            @Parameter(description = "Free-text query", required = true, example = "Breaking Bad")
            @RequestParam String query,
            @Parameter(schema = @Schema(allowableValues = {"MOVIES", "TV", "BOTH"}, defaultValue = "BOTH"))
            @RequestParam(required = false, defaultValue = "BOTH") String type,
            @Parameter(description = "Limit to one source", schema = @Schema(implementation = MediaSource.class))
            @RequestParam(required = false) String source) {
        if (query.isBlank()) {
            throw ApiException.badRequest("query must not be blank");
        }
        ContentTypeFilter filter = parseFilter(type);
        List<MediaSourceProvider> providers = source == null || source.isBlank()
                ? sourceRegistry.all()
                : List.of(provider(parseSource(source)));

        List<CompletableFuture<List<ContentMetadata>>> futures = providers.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.search(query, filter);
                    } catch (Exception e) {
                        log.warn("Search failed for source {}: {}", p.source(), e.getMessage(), e);
                        return List.<ContentMetadata>of();
                    }
                }))
                .toList();

        List<SearchResultDto> results = new ArrayList<>();
        for (CompletableFuture<List<ContentMetadata>> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS).forEach(c -> results.add(toDto(c)));
            } catch (Exception e) {
                log.warn("Search future failed: {}", e.getMessage());
            }
        }
        return results;
    }

    @Operation(summary = "Enabled sources, their languages, and download defaults")
    @GetMapping("/sources")
    public SourcesDto sources() {
        List<SourceDto> sources = sourceRegistry.all().stream()
                .sorted(Comparator.comparing(MediaSourceProvider::source))
                .map(p -> new SourceDto(p.source(), p.source().getDisplayName(),
                        p.supportedLanguages().stream().sorted().toList()))
                .toList();
        return new SourcesDto(sources,
                properties.getDownload().getDefaultLanguage(),
                properties.getDownload().getDefaultQuality(),
                QUALITIES);
    }

    @Operation(summary = "Episodes really available on the source",
            description = "VixSrc: TMDB episodes filtered by the VixSrc catalogue (falls back to TMDB when the catalogue is unavailable). "
                    + "RaiPlay: the program's episode listings. Each episode has its own sourceRef.")
    @PostMapping("/content/episodes")
    public List<EpisodeDto> episodes(@Valid @RequestBody EpisodesRequest req) {
        MediaSourceProvider provider = provider(req.source());
        SourceMetadata meta = sourceRefCodec.decode(req.sourceRef(), req.source());
        ContentMetadata show = ContentMetadata.builder()
                .source(req.source())
                .sourceMetadata(meta)
                .contentType(DownloadTask.ContentType.TV)
                .tmdbId(meta instanceof VixSrcMetadata v ? v.tmdbId() : null)
                .title(req.title())
                .build();
        List<EpisodeRef> refs = provider.listEpisodes(show, req.season());
        return refs.stream()
                .sorted(Comparator.comparingInt(EpisodeRef::season).thenComparingInt(EpisodeRef::episode))
                .map(r -> new EpisodeDto(r.season(), r.episode(), r.name(), sourceRefCodec.encode(r.sourceMetadata())))
                .toList();
    }

    @Operation(summary = "Audio, subtitles and qualities a title really has",
            description = "Reads the title's playlist on the source without downloading. For a show, the first episode "
                    + "the source has is checked; later episodes may differ. `known=false` when the source can't tell.")
    @PostMapping("/content/media-info")
    public MediaInfoDto mediaInfo(@Valid @RequestBody MediaInfoRequest req) {
        MediaSourceProvider provider = provider(req.source());
        SourceMetadata meta = sourceRefCodec.decode(req.sourceRef(), req.source());
        ContentMetadata content = ContentMetadata.builder()
                .source(req.source())
                .sourceMetadata(meta)
                .contentType(req.contentType())
                .tmdbId(meta instanceof VixSrcMetadata v ? v.tmdbId() : null)
                .build();
        Optional<MediaInfo> info;
        try {
            info = provider.mediaInfo(content, null, null);
        } catch (Exception e) {
            log.warn("Media info failed for {} {}: {}", req.source(), req.sourceRef(), e.getMessage());
            info = Optional.empty();
        }
        if (info.isEmpty()) {
            return new MediaInfoDto(false, List.of(), List.of(), QUALITIES);
        }
        List<String> qualities = new ArrayList<>();
        qualities.add("best");
        info.get().heights().forEach(h -> qualities.add(String.valueOf(h)));
        if (info.get().heights().size() > 1) {
            qualities.add("worst");
        }
        return new MediaInfoDto(true,
                info.get().audio().stream().map(ContentController::toTrackDto).toList(),
                info.get().subtitles().stream().map(ContentController::toTrackDto).toList(),
                qualities);
    }

    @Operation(summary = "A title's trailer on YouTube",
            description = "From TMDB: an official trailer in the default language when there is one, else in English "
                    + "or another language, else a teaser. `url` is null when TMDB has none.")
    @GetMapping("/content/trailer")
    public TrailerDto trailer(
            @Parameter(required = true) @RequestParam DownloadTask.ContentType contentType,
            @Parameter(required = true, example = "976893") @RequestParam int tmdbId) {
        return tmdbService.getTrailer(tmdbId, contentType == DownloadTask.ContentType.TV,
                        properties.getDownload().getDefaultLanguage())
                .map(t -> new TrailerDto(t[0], t[1]))
                .orElse(new TrailerDto(null, null));
    }

    private static MediaInfoDto.TrackDto toTrackDto(MediaInfo.Track t) {
        return new MediaInfoDto.TrackDto(t.language(), t.name(), t.forced());
    }

    // ------------------------------------------------------------------ helpers

    private SearchResultDto toDto(ContentMetadata c) {
        DownloadTask.ContentType type = c.getContentType() != null ? c.getContentType()
                : (c.getNumberOfSeasons() != null ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE);
        return new SearchResultDto(
                c.getSource(),
                type,
                c.getTitle(),
                c.getOriginalTitle(),
                c.getYear(),
                c.getOverview(),
                c.getVoteAverage() != null && c.getVoteAverage() > 0 ? c.getVoteAverage() : null,
                c.getTmdbId(),
                externalUrl(c, type),
                c.getPosterUrl(),
                type == DownloadTask.ContentType.TV ? seasons(c) : List.of(),
                c.getTotalEpisodes(),
                sourceRefCodec.encode(c.getSourceMetadata()));
    }

    private static List<SeasonDto> seasons(ContentMetadata c) {
        Map<Integer, Integer> perSeason = c.getEpisodesPerSeason();
        if (perSeason != null && !perSeason.isEmpty()) {
            return perSeason.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new SeasonDto(e.getKey(), e.getValue()))
                    .toList();
        }
        if (c.getNumberOfSeasons() != null && c.getNumberOfSeasons() > 0) {
            return IntStream.rangeClosed(1, c.getNumberOfSeasons()).mapToObj(n -> new SeasonDto(n, null)).toList();
        }
        return List.of();
    }

    private static String externalUrl(ContentMetadata c, DownloadTask.ContentType type) {
        return switch (c.getSource()) {
            case VIXSRC -> c.getTmdbId() == null ? null
                    : "https://www.themoviedb.org/" + (type == DownloadTask.ContentType.MOVIE ? "movie" : "tv")
                    + "/" + c.getTmdbId();
            case RAIPLAY -> c.getSourceMetadata() instanceof RaiPlayMetadata m ? m.webUrl() : null;
            case LINK -> null; // never a search result
        };
    }

    private static ContentTypeFilter parseFilter(String type) {
        String t = type == null ? "BOTH" : type.trim().toUpperCase();
        return switch (t) {
            case "MOVIE", "MOVIES" -> ContentTypeFilter.MOVIES;
            case "TV" -> ContentTypeFilter.TV;
            case "BOTH", "" -> ContentTypeFilter.BOTH;
            default -> throw ApiException.badRequest("Unknown type '" + type + "' (expected MOVIES, TV or BOTH)");
        };
    }

    static MediaSource parseSource(String source) {
        try {
            return MediaSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown source '" + source + "'");
        }
    }

    MediaSourceProvider provider(MediaSource source) {
        try {
            return sourceRegistry.get(source);
        } catch (IllegalStateException e) {
            throw ApiException.badRequest("Source " + source + " is not enabled");
        }
    }
}
