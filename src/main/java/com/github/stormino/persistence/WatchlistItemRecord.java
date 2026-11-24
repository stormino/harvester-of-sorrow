package com.github.stormino.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("watchlist_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistItemRecord {
    @Id
    private String id;
    private String userName;
    private String titleKey;
    private String contentType;
    private Integer tmdbId;
    private String title;
    private Integer year;
    private String overview;
    private Double rating;
    private String posterUrl;
    private String externalUrl;
    private String streams;          // JSON array of SearchResultDto
    private LocalDateTime createdAt;
    private String releaseDate;      // ISO date (SQLite has no date type)
    private String names;            // JSON array of other names (original, local title)
    private int waiting;             // SQLite boolean 1/0: saved before any source had it; the daily check looks for it
    private LocalDateTime checkedAt;
    private LocalDateTime availableSince;
    private String availableSources; // e.g. "VIXSRC,XDCC"
    private String availableQuery;   // the name that found it: what the search page should search
    private LocalDateTime seenAt;
}
