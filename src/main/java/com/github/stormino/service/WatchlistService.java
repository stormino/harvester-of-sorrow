package com.github.stormino.service;

import com.github.stormino.api.dto.SearchResultDto;
import com.github.stormino.api.dto.WatchlistItemDto;
import com.github.stormino.api.dto.WatchlistRequest;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.persistence.WatchlistItemRecord;
import com.github.stormino.persistence.WatchlistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Titles each user saved for later. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private static final TypeReference<List<SearchResultDto>> STREAMS = new TypeReference<>() {};

    private final WatchlistItemRepository repository;
    private final ObjectMapper objectMapper;

    public List<WatchlistItemDto> list(String user) {
        return repository.findByUser(user).stream().map(this::toDto).toList();
    }

    /**
     * Saves the title. When the user already saved it, refreshes its details (keeping the date
     * it was saved): a search can find more about a title than when it was first saved, e.g.
     * VixSrc results that arrived after the XDCC ones.
     */
    public WatchlistItemDto add(String user, WatchlistRequest req) {
        String streams = objectMapper.writeValueAsString(req.streams() == null ? List.of() : req.streams());
        Optional<WatchlistItemRecord> existing = repository.findByUserAndKey(user, req.titleKey());
        if (existing.isPresent()) {
            WatchlistItemRecord r = existing.get();
            // Never lose details: keep the saved ones where the request has none
            boolean noStreams = req.streams() == null || req.streams().isEmpty();
            repository.updateDetails(r.getId(),
                    req.tmdbId() != null ? req.tmdbId() : r.getTmdbId(),
                    req.title().strip(),
                    req.year() != null ? req.year() : r.getYear(),
                    req.overview() != null ? req.overview() : r.getOverview(),
                    req.rating() != null ? req.rating() : r.getRating(),
                    req.posterUrl() != null ? req.posterUrl() : r.getPosterUrl(),
                    req.externalUrl() != null ? req.externalUrl() : r.getExternalUrl(),
                    noStreams ? r.getStreams() : streams);
            return repository.findById(r.getId()).map(this::toDto).orElseThrow();
        }
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        repository.insert(id, user, req.titleKey(), req.contentType().name(), req.tmdbId(), req.title().strip(),
                req.year(), req.overview(), req.rating(), req.posterUrl(), req.externalUrl(), streams, now);
        log.info("Watchlist of '{}': added '{}' ({})", user, req.title(), req.titleKey());
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

    private WatchlistItemDto toDto(WatchlistItemRecord r) {
        List<SearchResultDto> streams;
        try {
            streams = objectMapper.readValue(r.getStreams(), STREAMS);
        } catch (Exception e) {
            log.warn("Unreadable streams for watchlist item {}: {}", r.getId(), e.getMessage());
            streams = List.of();
        }
        return new WatchlistItemDto(r.getId(), r.getTitleKey(), DownloadTask.ContentType.valueOf(r.getContentType()),
                r.getTmdbId(), r.getTitle(), r.getYear(), r.getOverview(), r.getRating(), r.getPosterUrl(),
                r.getExternalUrl(), streams, r.getCreatedAt());
    }
}
