package com.github.stormino.service.link.xdcc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Searches every XDCC source at once and merges the hits (one per server/channel/bot/pack).
 * xdcc.eu is the primary source: listed first, and its entry wins when both list the same pack;
 * the PW Search Engine adds what xdcc.eu does not index.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "hos.link.xdcc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XdccSearch {

    private static final int MAX_RESULTS = 1500;

    public record SourceStatus(String name, boolean available, String message) {
    }

    public record Result(List<XdccHit> hits, List<SourceStatus> sources) {
    }

    private final PwSearchEngine pw;
    private final XdccEuSearch xdccEu;

    public XdccSearch(PwSearchEngine pw, XdccEuSearch xdccEu) {
        this.pw = pw;
        this.xdccEu = xdccEu;
    }

    public List<SourceStatus> status() {
        List<SourceStatus> out = new ArrayList<>();
        out.add(new SourceStatus(XdccEuSearch.SOURCE, xdccEu.enabled(), xdccEu.enabled() ? null : "disabled"));
        if (!pw.enabled()) {
            return out;
        }
        PwSearchEngine.Status s = pw.status();
        out.add(new SourceStatus(PwSearchEngine.SOURCE, s.available(), s.available()
                ? s.lists() + " lists on " + s.networks() + " networks" : s.message()));
        return out;
    }

    public Result search(String words) {
        var pwFuture = pw.enabled()
                ? CompletableFuture.supplyAsync(() -> pw.search(words))
                : CompletableFuture.completedFuture(new PwSearchEngine.SearchResult(List.of(), 0, 0));
        var euFuture = CompletableFuture.supplyAsync(() -> {
            if (!xdccEu.enabled()) {
                return new SourceOutcome(List.of(), new SourceStatus(XdccEuSearch.SOURCE, false, "disabled"));
            }
            try {
                List<XdccHit> hits = xdccEu.search(words);
                return new SourceOutcome(hits, new SourceStatus(XdccEuSearch.SOURCE, true, hits.size() + " packs"));
            } catch (Exception e) {
                log.warn("xdcc.eu search failed: {}", e.getMessage());
                return new SourceOutcome(List.of(), new SourceStatus(XdccEuSearch.SOURCE, false, e.getMessage()));
            }
        });
        PwSearchEngine.SearchResult pwResult = pwFuture.join();
        SourceOutcome eu = euFuture.join();

        if (!pw.enabled()) {
            return new Result(cap(eu.hits()), List.of(eu.status()));
        }
        PwSearchEngine.Status pwStatus = pw.status();
        SourceStatus pwSource = pwStatus.available()
                ? new SourceStatus(PwSearchEngine.SOURCE, true, pwResult.packs().size() + " packs from "
                        + (pwResult.lists() - pwResult.failedLists()) + " of " + pwResult.lists() + " lists")
                : new SourceStatus(PwSearchEngine.SOURCE, false, pwStatus.message());

        Map<String, XdccHit> merged = new LinkedHashMap<>();
        eu.hits().forEach(h -> merged.putIfAbsent(h.key(), h));
        pwResult.packs().forEach(h -> merged.putIfAbsent(h.key(), h));
        return new Result(cap(new ArrayList<>(merged.values())), List.of(eu.status(), pwSource));
    }

    private static List<XdccHit> cap(List<XdccHit> found) {
        List<XdccHit> hits = new ArrayList<>(found);
        hits.sort(Comparator.comparing(XdccHit::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(XdccHit::network).thenComparing(XdccHit::bot).thenComparingInt(XdccHit::pack));
        return hits.size() > MAX_RESULTS ? List.copyOf(hits.subList(0, MAX_RESULTS)) : hits;
    }

    private record SourceOutcome(List<XdccHit> hits, SourceStatus status) {
    }
}
