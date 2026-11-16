package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.dto.xdcc.XdccPackDto;
import com.github.stormino.api.dto.xdcc.XdccSearchDto;
import com.github.stormino.api.dto.xdcc.XdccStatusDto;
import com.github.stormino.service.link.xdcc.PwSearchEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "XDCC", description = "Search XDCC packs on IRC (PW Search Engine pack lists)")
@RestController
@RequestMapping("/api/v1/xdcc")
public class XdccController {

    private final ObjectProvider<PwSearchEngine> engine;

    public XdccController(ObjectProvider<PwSearchEngine> engine) {
        this.engine = engine;
    }

    @Operation(summary = "Whether XDCC search is available")
    @GetMapping("/status")
    public XdccStatusDto status() {
        PwSearchEngine e = engine.getIfAvailable();
        if (e == null) {
            return new XdccStatusDto(false, "XDCC is disabled (XDCC_ENABLED=false)", 0, 0, null, "");
        }
        PwSearchEngine.Status s = e.status();
        return new XdccStatusDto(s.available(), s.message(), s.networks(), s.lists(), s.updatedAt(), e.userAgent());
    }

    @Operation(summary = "Search packs by file name",
            description = "Queries every pack list of the PW Search Engine database. Pass the chosen packs' "
                    + "`url`s to POST /links/resolve-batch, then queue them with POST /links/downloads.")
    @GetMapping("/search")
    public XdccSearchDto search(@RequestParam String query) {
        PwSearchEngine e = engine.getIfAvailable();
        if (e == null) {
            throw ApiException.badRequest("XDCC is disabled");
        }
        if (query.strip().length() < 3) {
            throw ApiException.badRequest("Type at least 3 characters");
        }
        PwSearchEngine.SearchResult r = e.search(query);
        return new XdccSearchDto(r.packs().stream()
                .map(p -> new XdccPackDto(p.network(), p.channel(), p.bot(), p.pack(), p.size(), p.approxBytes(),
                        p.fileName(), p.link().toUrl()))
                .toList(), r.lists(), r.failedLists());
    }
}
