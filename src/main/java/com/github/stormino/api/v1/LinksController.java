package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.TaskMapper;
import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.api.dto.link.LinkDownloadRequest;
import com.github.stormino.api.dto.link.LinkFileDto;
import com.github.stormino.api.dto.link.LinkGuessDto;
import com.github.stormino.api.dto.link.LinkPartDto;
import com.github.stormino.api.dto.link.LinkProviderDto;
import com.github.stormino.api.dto.link.LinkResolutionDto;
import com.github.stormino.api.dto.link.ResolveLinkRequest;
import com.github.stormino.api.dto.link.ResolveLinksRequest;
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
import com.github.stormino.service.link.archive.ArchiveFormats;
import com.github.stormino.util.MediaFiles;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Tag(name = "Links", description = "Download from a link (Mega, direct HTTP files, ...)")
@RestController
@RequestMapping("/api/v1/links")
@RequiredArgsConstructor
public class LinksController {

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
        return describe(provider, resolution);
    }

    @Operation(summary = "Inspect several links as one group",
            description = "Like POST /links/resolve for a list of links of the same provider (e.g. XDCC packs "
                    + "picked from a search), returned as one folder-like resolution.")
    @ApiResponse(responseCode = "400", description = "Unsupported links, or links of different providers")
    @PostMapping("/resolve-batch")
    public LinkResolutionDto resolveBatch(@Valid @RequestBody ResolveLinksRequest req) {
        LinkProvider provider = null;
        List<LinkFile> files = new ArrayList<>();
        try {
            for (String raw : req.urls()) {
                String url = raw.strip();
                LinkProvider p = providers.select(url, null);
                if (provider != null && !provider.id().equals(p.id())) {
                    throw ApiException.badRequest("All links must use the same provider");
                }
                provider = p;
                files.addAll(p.resolve(url).files());
            }
        } catch (LinkException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        String name = req.name() != null && !req.name().isBlank() ? req.name().strip()
                : files.size() == 1 ? files.get(0).fileName() : files.size() + " files";
        return describe(provider, new LinkResolution(provider.id(), req.urls().get(0), files.size() > 1, name, files, 0));
    }

    /** Files (archive volumes folded), guesses and TMDB candidates; also starts the provider's preparation. */
    private LinkResolutionDto describe(LinkProvider provider, LinkResolution resolution) {
        provider.prepare();

        List<LinkFileDto> files = groupArchiveVolumes(resolution.files()).stream()
                .map(g -> new LinkFileDto(g.first().url(), g.first().fileName(), g.size(), g.first().folders(),
                        isVideo(g.first().fileName()), ArchiveFormats.isArchive(g.first().fileName()),
                        g.parts().stream().map(p -> new LinkPartDto(p.url(), p.fileName(), p.size())).toList(),
                        toDto(guess(g.first()))))
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
            boolean archive = ArchiveFormats.isArchive(item.fileName());
            if (tv && !archive && (item.season() == null || item.episode() == null)) {
                throw ApiException.badRequest("Season and episode are required for " + item.fileName());
            }
            if ((item.season() != null && item.season() < 0) || (item.episode() != null && item.episode() < 0)) {
                throw ApiException.badRequest("Season and episode cannot be negative");
            }
            List<LinkFile> parts = item.parts() == null ? List.of() : item.parts().stream()
                    .map(p -> new LinkFile(p.url(), p.fileName(), p.size(), null)).toList();
            for (LinkFile part : parts) {
                if (provider.matchScore(part.url()) <= LinkProvider.SCORE_NONE) {
                    throw ApiException.badRequest(provider.displayName() + " cannot download " + part.fileName());
                }
            }
            String episodeName = item.episodeName();
            if (tv && (episodeName == null || episodeName.isBlank()) && item.tmdbId() != null
                    && item.season() != null && item.episode() != null) {
                episodeName = episodeNames
                        .computeIfAbsent(item.tmdbId() + ":" + item.season(),
                                k -> matcher.episodeNames(item.tmdbId(), item.season()))
                        .get(item.episode());
            }
            specs.add(new LinkTaskSpec(provider.id(), req.url(),
                    new LinkFile(item.fileUrl(), item.fileName(), item.size(), null), parts,
                    item.contentType(), item.tmdbId(), item.title(), item.year(),
                    item.season(), item.episode(), episodeName));
        }
        return queue.enqueueLinks(specs).stream().map(TaskMapper::toDto).toList();
    }

    /** Folder: its name, typed TV when its files look like episodes. Single file: the file's guess. */
    static ReleaseNameParser.Guess overallGuess(LinkResolution resolution) {
        if (!resolution.folder() && resolution.files().size() == 1) {
            return guess(resolution.files().get(0));
        }
        ReleaseNameParser.Guess folder = ReleaseNameParser.parseFolderName(resolution.name());
        List<ReleaseNameParser.Guess> media = resolution.files().stream()
                .filter(f -> isVideo(f.fileName()) || ArchiveFormats.isArchive(f.fileName()))
                .map(LinksController::guess)
                .toList();
        boolean episodes = media.stream().anyMatch(g -> g.type() == DownloadTask.ContentType.TV);
        String title = folder.title();
        Integer year = folder.year();
        Integer season = folder.season();
        if (title.isEmpty() && !media.isEmpty()) {
            title = media.get(0).title();
            year = year != null ? year : media.get(0).year();
            season = season != null ? season : media.get(0).season();
        }
        DownloadTask.ContentType type = episodes || folder.type() == DownloadTask.ContentType.TV
                ? DownloadTask.ContentType.TV : DownloadTask.ContentType.MOVIE;
        return new ReleaseNameParser.Guess(type, title, year, season, null, null);
    }

    /** Guess for one file; an archive named like a season pack ("Show.S02.rar") is TV with that season. */
    static ReleaseNameParser.Guess guess(LinkFile f) {
        ReleaseNameParser.Guess g = ReleaseNameParser.parse(f.fileName(), f.folders());
        if (g.type() == DownloadTask.ContentType.MOVIE && ArchiveFormats.isArchive(f.fileName())) {
            ReleaseNameParser.Guess pack = ReleaseNameParser.parseFolderName(ArchiveFormats.baseName(f.fileName()));
            if (pack.season() != null && !pack.title().isEmpty()) {
                return pack;
            }
        }
        return g;
    }

    /** One file of the list, multi-part archives folded into their first volume. */
    record Grouped(LinkFile first, List<LinkFile> parts) {
        Long size() {
            if (parts.isEmpty()) {
                return first.size();
            }
            return parts.stream().allMatch(p -> p.size() != null) ? parts.stream().mapToLong(LinkFile::size).sum() : null;
        }
    }

    /** Folds the volumes of each multi-part archive (same folder, same set name) into one entry. */
    static List<Grouped> groupArchiveVolumes(List<LinkFile> files) {
        Map<String, List<LinkFile>> sets = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (LinkFile f : files) {
            Optional<ArchiveFormats.Volume> v = ArchiveFormats.volume(f.fileName());
            if (v.isEmpty()) {
                order.add(f);
                continue;
            }
            String key = String.join("/", f.folders()) + "/" + v.get().kind() + ":" + v.get().set().toLowerCase(Locale.ROOT);
            sets.computeIfAbsent(key, k -> {
                order.add(k);
                return new ArrayList<>();
            }).add(f);
        }
        List<Grouped> out = new ArrayList<>();
        for (Object o : order) {
            if (o instanceof LinkFile f) {
                out.add(new Grouped(f, List.of()));
                continue;
            }
            List<LinkFile> volumes = new ArrayList<>(sets.get(o));
            volumes.sort(Comparator.comparingInt(f -> ArchiveFormats.volume(f.fileName()).orElseThrow().order()));
            out.add(new Grouped(volumes.get(0), volumes.size() > 1 ? List.copyOf(volumes) : List.of()));
        }
        return out;
    }

    static boolean isVideo(String fileName) {
        return MediaFiles.isVideo(fileName);
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
