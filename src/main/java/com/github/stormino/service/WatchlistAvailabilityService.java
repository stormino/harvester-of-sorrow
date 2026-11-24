package com.github.stormino.service;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.persistence.WatchlistItemRecord;
import com.github.stormino.persistence.WatchlistItemRepository;
import com.github.stormino.service.link.xdcc.XdccResults;
import com.github.stormino.service.link.xdcc.XdccSearch;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.github.stormino.util.TitleMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Looks for the titles saved before any source had them ("waiting"): VixSrc by TMDB id, RaiPlay and
 * XDCC by name. A title found anywhere is flagged as available, with the name that found it, so the
 * search page can be opened on it. Runs once a day ({@link WatchlistScheduler}) and on demand.
 */
@Slf4j
@Service
public class WatchlistAvailabilityService {

    /** What a check found: where, and the name to search for it. Empty sources: not yet. */
    public record Found(Set<String> sources, String query) {
        public boolean any() {
            return !sources.isEmpty();
        }
    }

    private final WatchlistItemRepository repository;
    private final WatchlistService watchlist;
    private final MediaSourceRegistry sources;
    private final ObjectProvider<XdccSearch> xdccSearch;
    private final ObjectProvider<XdccResults> xdccResults;

    public WatchlistAvailabilityService(WatchlistItemRepository repository, WatchlistService watchlist,
                                        MediaSourceRegistry sources, ObjectProvider<XdccSearch> xdccSearch,
                                        ObjectProvider<XdccResults> xdccResults) {
        this.repository = repository;
        this.watchlist = watchlist;
        this.sources = sources;
        this.xdccSearch = xdccSearch;
        this.xdccResults = xdccResults;
    }

    /** Checks every user's waiting titles; returns how many became available. */
    public int checkAll() {
        return check(repository.findWaiting());
    }

    /** Checks one user's waiting titles; returns how many became available. */
    public int checkUser(String user) {
        return check(repository.findWaitingByUser(user));
    }

    private int check(List<WatchlistItemRecord> waiting) {
        if (waiting.isEmpty()) {
            log.debug("Watchlist check: nothing waiting");
            return 0;
        }
        // Several users can wait for the same title: look it up once
        Map<String, List<WatchlistItemRecord>> byTitle = new LinkedHashMap<>();
        for (WatchlistItemRecord r : waiting) {
            byTitle.computeIfAbsent(r.getTitleKey(), k -> new ArrayList<>()).add(r);
        }
        log.info("Watchlist check: {} title(s) waiting", byTitle.size());
        int found = 0;
        for (List<WatchlistItemRecord> same : byTitle.values()) {
            WatchlistItemRecord first = same.get(0);
            LocalDateTime now = LocalDateTime.now();
            Found f;
            try {
                f = find(first);
            } catch (Exception e) {
                log.warn("Watchlist check of '{}' failed: {}", first.getTitle(), e.getMessage(), e);
                continue;
            }
            for (WatchlistItemRecord r : same) {
                if (f.any()) {
                    repository.markAvailable(r.getId(), now, String.join(",", f.sources()), f.query());
                } else {
                    repository.markChecked(r.getId(), now);
                }
            }
            if (f.any()) {
                found++;
                log.info("Watchlist: '{}' can now be downloaded from {} (search \"{}\")",
                        first.getTitle(), f.sources(), f.query());
            }
        }
        log.info("Watchlist check done: {} of {} title(s) now available", found, byTitle.size());
        return found;
    }

    /** Looks the title up on every source. */
    Found find(WatchlistItemRecord r) {
        DownloadTask.ContentType type = DownloadTask.ContentType.valueOf(r.getContentType());
        List<String> names = new ArrayList<>();
        names.add(r.getTitle());
        names.addAll(watchlist.names(r));

        Set<String> where = new LinkedHashSet<>();
        String query = null;

        Optional<MediaSourceProvider> vixsrc = provider(MediaSource.VIXSRC);
        if (vixsrc.isPresent() && r.getTmdbId() != null) {
            ContentMetadata c = ContentMetadata.builder().source(MediaSource.VIXSRC).contentType(type)
                    .tmdbId(r.getTmdbId()).title(r.getTitle()).build();
            if (vixsrc.get().checkAvailability(c, Set.of(VixSrcAvailabilityService.EPISODE_LIST_LANG)).isAvailable()) {
                where.add(MediaSource.VIXSRC.name());
                query = r.getTitle(); // the streaming search goes through TMDB, which knows the saved title
            }
        }

        Optional<MediaSourceProvider> raiplay = provider(MediaSource.RAIPLAY);
        if (raiplay.isPresent()) {
            ContentTypeFilter filter = type == DownloadTask.ContentType.TV ? ContentTypeFilter.TV : ContentTypeFilter.MOVIES;
            for (String name : names) {
                boolean hit = raiplay.get().search(name, filter).stream()
                        .anyMatch(c -> c.isAvailable() && names.stream().anyMatch(n -> TitleMatch.same(n, c.getTitle()))
                                && TitleMatch.yearsMatch(c.getYear(), r.getYear()));
                if (hit) {
                    where.add(MediaSource.RAIPLAY.name());
                    query = query != null ? query : name;
                    break;
                }
            }
        }

        XdccSearch xdcc = xdccSearch.getIfAvailable();
        XdccResults grouping = xdccResults.getIfAvailable();
        if (xdcc != null && grouping != null) {
            for (String name : names) {
                if (name.strip().length() < 3) {
                    continue;
                }
                boolean hit = grouping.group(xdcc.search(name.strip()).hits(), name.strip()).stream()
                        .anyMatch(g -> g.type() == type && !g.releases().isEmpty() && (
                                r.getTmdbId() != null && r.getTmdbId().equals(g.tmdbId())
                                        || names.stream().anyMatch(n -> TitleMatch.same(n, g.title()) || TitleMatch.same(n, g.tmdbTitle()))
                                        && TitleMatch.yearsMatch(g.year(), r.getYear())));
                if (hit) {
                    where.add("XDCC");
                    query = query != null ? query : name;
                    break;
                }
            }
        }
        return new Found(where, query);
    }

    private Optional<MediaSourceProvider> provider(MediaSource source) {
        return sources.all().stream().filter(p -> p.source() == source).findFirst();
    }
}
