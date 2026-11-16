package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.TaskMapper;
import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.api.dto.link.LinkDownloadRequest;
import com.github.stormino.api.dto.link.LinkFileDto;
import com.github.stormino.api.dto.link.LinkGuessDto;
import com.github.stormino.api.dto.link.LinkProviderDto;
import com.github.stormino.api.dto.link.LinkResolutionDto;
import com.github.stormino.api.dto.link.ResolveLinkRequest;
import com.github.stormino.api.dto.link.TmdbMatchDto;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.link.LinkException;
import com.github.stormino.service.link.LinkFile;
import com.github.stormino.service.link.LinkMatchService;
import com.github.stormino.service.link.LinkProvider;
import com.github.stormino.service.link.LinkProviderRegistry;
import com.github.stormino.service.link.LinkResolution;
import com.github.stormino.service.link.LinkTaskSpec;
import com.github.stormino.service.link.ProviderStatus;
import com.github.stormino.service.link.ReleaseNameParser;
import com.github.stormino.util.PathUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Tag(name = "Links", description = "Download from a link (Mega, direct HTTP files, ...)")
@RestController
@RequestMapping("/api/v1/links")
@RequiredArgsConstructor
public class LinksController {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mkv", ".mp4", ".m4v", ".avi", ".mov", ".wmv",
            ".ts", ".m2ts", ".mts", ".webm", ".mpg", ".mpeg", ".flv", ".ogv", ".3gp", ".vob", ".divx");

    private final LinkProviderRegistry providers;
    private final LinkMatchService matcher;
    private final DownloadQueueService queue;

    @Operation(summary = "Link providers and their readiness")
    @GetMapping("/providers")
    public List<LinkProviderDto> providers() {
        return providers.all().stream().map(LinksController::toDto).toList();
    }

    @Operation(summary = "Inspect a link",
            description = "Lists the file(s) behind the link without downloading, guesses title/year/episode from "
                    + "the names and suggests TMDB matches. The provider is detected from the URL unless `provider` "
                    + "is given. Also starts the provider's preparation (e.g. Mega's proxy warm-up) in the background.")
    @ApiResponse(responseCode = "400", description = "Unsupported, invalid or unreachable link")
    @PostMapping("/resolve")
    public LinkResolutionDto resolve(@Valid @RequestBody ResolveLinkRequest req) {
        String url = req.url().strip();
        LinkProvider provider;
        LinkResolution resolution;
        try {
            provider = providers.select(url, req.provider());
            resolution = provider.resolve(url);
        } catch (LinkException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (resolution.files().isEmpty()) {
            throw ApiException.badRequest("The link contains no files");
        }
        provider.prepare();

        List<LinkFileDto> files = resolution.files().stream()
                .map(f -> new LinkFileDto(f.url(), f.fileName(), f.size(), f.folders(), isVideo(f.fileName()),
                        toDto(ReleaseNameParser.parse(f.fileName(), f.folders()))))
                .toList();
        ReleaseNameParser.Guess overall = overallGuess(resolution);
        List<TmdbMatchDto> matches = matcher.candidates(overall).stream()
                .filter(c -> c.getTmdbId() != null).map(LinksController::toDto).toList();
        return new LinkResolutionDto(provider.id(), provider.displayName(), resolution.url(), resolution.folder(),
                resolution.name(), resolution.truncated(), files, toDto(overall), matches, matcher.isAvailable());
    }

    @Operation(summary = "Search TMDB for the title of a link", description = "For correcting a wrong guess.")
    @GetMapping("/tmdb")
    public List<TmdbMatchDto> searchTmdb(@RequestParam String query,
                                         @RequestParam(defaultValue = "MOVIE") DownloadTask.ContentType type,
                                         @RequestParam(required = false) Integer year) {
        return matcher.search(query, type, year).stream()
                .filter(c -> c.getTmdbId() != null).map(LinksController::toDto).toList();
    }

    @Operation(summary = "Queue files of a link",
            description = "One task per item. Items must come from POST /links/resolve of the same link; the "
                    + "library path is built from the confirmed title/year (and season/episode for TV).")
    @ApiResponse(responseCode = "201", description = "Tasks created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @PostMapping("/downloads")
    @ResponseStatus(HttpStatus.CREATED)
    public List<TaskDto> download(@Valid @RequestBody LinkDownloadRequest req) {
        LinkProvider provider = providers.find(req.provider())
                .orElseThrow(() -> ApiException.badRequest("Unknown or disabled provider: " + req.provider()));
        Map<String, Map<Integer, String>> episodeNames = new HashMap<>();
        List<LinkTaskSpec> specs = new ArrayList<>();
        for (LinkDownloadRequest.Item item : req.items()) {
            if (provider.matchScore(item.fileUrl()) <= LinkProvider.SCORE_NONE) {
                throw ApiException.badRequest(provider.displayName() + " cannot download " + item.fileName());
            }
            boolean tv = item.contentType() == DownloadTask.ContentType.TV;
            if (tv && (item.season() == null || item.episode() == null || item.season() < 0 || item.episode() < 0)) {
                throw ApiException.badRequest("Season and episode are required for " + item.fileName());
            }
            String episodeName = item.episodeName();
            if (tv && (episodeName == null || episodeName.isBlank()) && item.tmdbId() != null) {
                episodeName = episodeNames
                        .computeIfAbsent(item.tmdbId() + ":" + item.season(),
                                k -> matcher.episodeNames(item.tmdbId(), item.season()))
                        .get(item.episode());
            }
            specs.add(new LinkTaskSpec(provider.id(), req.url(),
                    new LinkFile(item.fileUrl(), item.fileName(), item.size(), null),
                    item.contentType(), item.tmdbId(), item.title(), item.year(),
                    item.season(), item.episode(), episodeName));
        }
        return queue.enqueueLinks(specs).stream().map(TaskMapper::toDto).toList();
    }

    /** Folder: its name, typed TV when its files look like episodes. Single file: the file's guess. */
    static ReleaseNameParser.Guess overallGuess(LinkResolution resolution) {
        if (!resolution.folder() && resolution.files().size() == 1) {
            LinkFile f = resolution.files().get(0);
            return ReleaseNameParser.parse(f.fileName(), f.folders());
        }
        ReleaseNameParser.Guess folder = ReleaseNameParser.parseFolderName(resolution.name());
        List<ReleaseNameParser.Guess> videos = resolution.files().stream()
                .filter(f -> isVideo(f.fileName()))
                .map(f -> ReleaseNameParser.parse(f.fileName(), f.folders()))
                .toList();
        boolean episodes = videos.stream().anyMatch(g -> g.type() == DownloadTask.ContentType.TV);
        String title = folder.title();
        Integer year = folder.year();
        if (title.isEmpty() && !videos.isEmpty()) {
            title = videos.get(0).title();
            year = year != null ? year : videos.get(0).year();
        }
        DownloadTask.ContentType type = episodes || folder.type() == DownloadTask.ContentType.TV
                ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE;
        return new ReleaseNameParser.Guess(type, title, year, folder.season(), null, null);
    }

    static boolean isVideo(String fileName) {
        return VIDEO_EXTENSIONS.contains(PathUtils.getExtension(fileName == null ? "" : fileName).toLowerCase(Locale.ROOT));
    }

    private static LinkProviderDto toDto(LinkProvider p) {
        ProviderStatus s = p.status();
        return new LinkProviderDto(p.id(), p.displayName(), s.state().name(), s.message(), s.done(), s.total());
    }

    private static LinkGuessDto toDto(ReleaseNameParser.Guess g) {
        return new LinkGuessDto(g.type(), g.title(), g.year(), g.season(), g.episode(), g.episodeTitle());
    }

    private static TmdbMatchDto toDto(ContentMetadata c) {
        return new TmdbMatchDto(c.getTmdbId(), c.getContentType(), c.getTitle(), c.getOriginalTitle(), c.getYear(),
                c.getOverview(), c.getPosterUrl());
    }
}
