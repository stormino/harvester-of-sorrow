package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.dto.xdcc.XdccPackDto;
import com.github.stormino.api.dto.xdcc.XdccSearchDto;
import com.github.stormino.api.dto.xdcc.XdccSourceDto;
import com.github.stormino.api.dto.xdcc.XdccStatusDto;
import com.github.stormino.service.link.xdcc.PwSearchEngine;
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

    public XdccController(ObjectProvider<XdccSearch> search, ObjectProvider<PwSearchEngine> pw) {
        this.search = search;
        this.pw = pw;
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
            description = "Queries every source at once and merges the hits. Pass the chosen packs' `url`s to "
                    + "POST /links/resolve-batch, then queue them with POST /links/downloads.")
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
        return new XdccSearchDto(
                r.hits().stream().map(h -> new XdccPackDto(h.source(), h.network(), h.channel(), h.bot(), h.pack(),
                        h.size(), h.approxBytes(), h.fileName(), h.link().toUrl())).toList(),
                r.sources().stream().map(x -> new XdccSourceDto(x.name(), x.available(), x.message())).toList());
    }
}
