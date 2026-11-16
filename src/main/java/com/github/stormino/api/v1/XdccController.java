package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.dto.xdcc.XdccQualityDto;
import com.github.stormino.api.dto.xdcc.XdccReleaseDto;
import com.github.stormino.api.dto.xdcc.XdccTitleDto;
import com.github.stormino.api.dto.xdcc.XdccSearchDto;
import com.github.stormino.api.dto.xdcc.XdccSourceDto;
import com.github.stormino.api.dto.xdcc.XdccStatusDto;
import com.github.stormino.service.link.ReleaseQuality;
import com.github.stormino.service.link.xdcc.PwSearchEngine;
import com.github.stormino.service.link.xdcc.XdccResults;
import com.github.stormino.service.link.xdcc.XdccSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "XDCC", description = "Search XDCC packs on IRC (PW Search Engine, xdcc.eu)")
@RestController
@RequestMapping("/api/v1/xdcc")
public class XdccController {

    private final ObjectProvider<XdccSearch> search;
    private final ObjectProvider<PwSearchEngine> pw;
    private final ObjectProvider<XdccResults> results;

    public XdccController(ObjectProvider<XdccSearch> search, ObjectProvider<PwSearchEngine> pw,
                          ObjectProvider<XdccResults> results) {
        this.search = search;
        this.pw = pw;
        this.results = results;
    }

    @Operation(summary = "Whether XDCC search is available, per source")
    @GetMapping("/status")
    public XdccStatusDto status() {
        XdccSearch s = search.getIfAvailable();
        if (s == null) {
            return new XdccStatusDto(false, List.of(new XdccSourceDto("XDCC", false, "disabled (XDCC_ENABLED=false)")), "");
        }
        List<XdccSourceDto> sources = s.status().stream()
                .map(x -> new XdccSourceDto(x.name(), x.available(), x.message())).toList();
        return new XdccStatusDto(sources.stream().anyMatch(XdccSourceDto::available), sources, pw.getObject().userAgent());
    }

    @Operation(summary = "Search packs by file name",
            description = "Queries every source at once, merges the bots offering the same file, folds multi-part "
                    + "archives, and groups releases by title (most relevant first, best quality first, TMDB poster "
                    + "for the top titles). Pass a release's `urls` to POST /links/resolve-batch, then queue with "
                    + "POST /links/downloads.")
    @GetMapping("/search")
    public XdccSearchDto search(@RequestParam String query) {
        XdccSearch s = search.getIfAvailable();
        if (s == null) {
            throw ApiException.badRequest("XDCC is disabled");
        }
        if (query.strip().length() < 3) {
            throw ApiException.badRequest("Type at least 3 characters");
        }
        XdccSearch.Result r = s.search(query.strip());
        List<XdccTitleDto> titles = results.getObject().group(r.hits(), query.strip()).stream()
                .map(XdccController::toDto).toList();
        return new XdccSearchDto(titles, r.hits().size(),
                r.sources().stream().map(x -> new XdccSourceDto(x.name(), x.available(), x.message())).toList());
    }

    private static XdccTitleDto toDto(XdccResults.TitleGroup g) {
        return new XdccTitleDto(g.title(), g.year(), g.type(), g.relevance(), g.tmdbId(), g.tmdbTitle(), g.posterUrl(),
                g.releases().stream().map(XdccController::toDto).toList());
    }

    private static XdccReleaseDto toDto(XdccResults.Release r) {
        ReleaseQuality q = r.quality();
        List<String> urls = r.parts().isEmpty() ? List.of(r.link().toUrl())
                : r.parts().stream().map(p -> p.link().toUrl()).toList();
        return new XdccReleaseDto(r.label(), r.episode(), r.size(), r.sizeBytes(),
                new XdccQualityDto(q.resolution(), q.source(), q.codec(), q.hdr(), q.audio(), q.languages(),
                        q.subtitles(), q.lowQuality()),
                r.format(), r.bots(), r.networks(), urls, r.complete(), r.missing());
    }
}
