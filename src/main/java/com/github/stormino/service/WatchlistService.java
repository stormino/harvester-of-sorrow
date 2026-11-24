package com.github.stormino.service;

import com.github.stormino.api.dto.SearchResultDto;
import com.github.stormino.api.dto.WatchlistItemDto;
import com.github.stormino.api.dto.WatchlistRequest;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.persistence.WatchlistItemRecord;
import com.github.stormino.persistence.WatchlistItemRepository;
import com.github.stormino.util.TitleMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Titles each user saved for later. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private static final TypeReference<List<SearchResultDto>> STREAMS = new TypeReference<>() {};
    private static final TypeReference<List<String>> NAMES = new TypeReference<>() {};

    private final WatchlistItemRepository repository;
    private final ObjectMapper objectMapper;

    public List<WatchlistItemDto> list(String user) {
        return repository.findByUser(user).stream().map(this::toDto).toList();
    }

    /**
     * Saves the title. When the user already saved it, refreshes its details (keeping the date
     * it was saved): a search can find more about a title than when it was first saved, e.g.
     * VixSrc results that arrived after the XDCC ones. A title saved while no source had it is
     * "waiting" until the daily check finds it, or until it is saved again from a search that
     * can download it.
     */
    public WatchlistItemDto add(String user, WatchlistRequest req) {
        String streams = objectMapper.writeValueAsString(req.streams() == null ? List.of() : req.streams());
        boolean availableNow = !Boolean.FALSE.equals(req.available());
        String releaseDate = req.releaseDate() == null ? null : req.releaseDate().toString();
        Optional<WatchlistItemRecord> existing = repository.findByUserAndKey(user, req.titleKey());
        if (existing.isPresent()) {
            WatchlistItemRecord r = existing.get();
            // Never lose details: keep the saved ones where the request has none
            boolean noStreams = req.streams() == null || req.streams().isEmpty();
            boolean noNames = req.names() == null || req.names().isEmpty();
            repository.updateDetails(r.getId(),
                    req.tmdbId() != null ? req.tmdbId() : r.getTmdbId(),
                    req.title().strip(),
                    req.year() != null ? req.year() : r.getYear(),
                    req.overview() != null ? req.overview() : r.getOverview(),
                    req.rating() != null ? req.rating() : r.getRating(),
                    req.posterUrl() != null ? req.posterUrl() : r.getPosterUrl(),
                    req.externalUrl() != null ? req.externalUrl() : r.getExternalUrl(),
                    noStreams ? r.getStreams() : streams,
                    releaseDate != null ? releaseDate : r.getReleaseDate(),
                    noNames ? r.getNames() : namesJson(req.title(), req.names()),
                    // Found by the user in a search: no longer waiting (and no "now available" badge)
                    availableNow ? 0 : r.getWaiting());
            return repository.findById(r.getId()).map(this::toDto).orElseThrow();
        }
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        repository.insert(id, user, req.titleKey(), req.contentType().name(), req.tmdbId(), req.title().strip(),
                req.year(), req.overview(), req.rating(), req.posterUrl(), req.externalUrl(), streams, now,
                releaseDate, namesJson(req.title(), req.names()), availableNow ? 0 : 1);
        log.info("Watchlist of '{}': added '{}' ({}){}", user, req.title(), req.titleKey(),
                availableNow ? "" : ", waiting until a source has it");
        return repository.findById(id).map(this::toDto).orElseThrow();
    }

    /** False when the user has no such entry. */
    public boolean remove(String user, String id) {
        boolean removed = repository.deleteByIdAndUser(id, user) > 0;
        if (removed) {
            log.info("Watchlist of '{}': removed {}", user, id);
        }
        return removed;
    }

    /** The user opened a title that became available: drop its "now available" badge. */
    public Optional<WatchlistItemDto> markSeen(String user, String id) {
        repository.markSeen(id, user, LocalDateTime.now());
        return repository.findById(id).filter(r -> r.getUserName().equals(user)).map(this::toDto);
    }

    /** Other names of the title, distinct and without the title itself. */
    private String namesJson(String title, List<String> names) {
        List<String> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                if (n == null || n.isBlank() || TitleMatch.same(n, title)
                        || out.stream().anyMatch(o -> TitleMatch.same(o, n))) {
                    continue;
                }
                out.add(n.strip());
            }
        }
        return objectMapper.writeValueAsString(out);
    }

    List<String> names(WatchlistItemRecord r) {
        if (r.getNames() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(r.getNames(), NAMES);
        } catch (Exception e) {
            log.warn("Unreadable names for watchlist item {}: {}", r.getId(), e.getMessage());
            return List.of();
        }
    }

    private WatchlistItemDto toDto(WatchlistItemRecord r) {
        List<SearchResultDto> streams;
        try {
            streams = objectMapper.readValue(r.getStreams(), STREAMS);
        } catch (Exception e) {
            log.warn("Unreadable streams for watchlist item {}: {}", r.getId(), e.getMessage());
            streams = List.of();
        }
        List<String> sources = r.getAvailableSources() == null || r.getAvailableSources().isBlank()
                ? List.of() : Arrays.asList(r.getAvailableSources().split(","));
        return new WatchlistItemDto(r.getId(), r.getTitleKey(), DownloadTask.ContentType.valueOf(r.getContentType()),
                r.getTmdbId(), r.getTitle(), r.getYear(), r.getOverview(), r.getRating(), r.getPosterUrl(),
                r.getExternalUrl(), streams, r.getCreatedAt(),
                r.getReleaseDate() == null ? null : LocalDate.parse(r.getReleaseDate()),
                names(r), r.getWaiting() == 1, r.getCheckedAt(), r.getAvailableSince(), sources, r.getAvailableQuery(),
                r.getAvailableSince() != null && r.getSeenAt() == null);
    }
}
