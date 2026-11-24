package com.github.stormino.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends CrudRepository<WatchlistItemRecord, String> {

    @Query("SELECT * FROM watchlist_item WHERE user_name = :userName ORDER BY created_at DESC")
    List<WatchlistItemRecord> findByUser(@Param("userName") String userName);

    @Query("SELECT * FROM watchlist_item WHERE user_name = :userName AND title_key = :titleKey")
    Optional<WatchlistItemRecord> findByUserAndKey(@Param("userName") String userName, @Param("titleKey") String titleKey);

    @Modifying
    @Query("INSERT INTO watchlist_item (id, user_name, title_key, content_type, tmdb_id, title, year, overview, rating, "
            + "poster_url, external_url, streams, created_at, release_date, names, waiting) VALUES (:id, :userName, :titleKey, "
            + ":contentType, :tmdbId, :title, :year, :overview, :rating, :posterUrl, :externalUrl, :streams, :createdAt, "
            + ":releaseDate, :names, :waiting)")
    void insert(@Param("id") String id,
                @Param("userName") String userName,
                @Param("titleKey") String titleKey,
                @Param("contentType") String contentType,
                @Param("tmdbId") Integer tmdbId,
                @Param("title") String title,
                @Param("year") Integer year,
                @Param("overview") String overview,
                @Param("rating") Double rating,
                @Param("posterUrl") String posterUrl,
                @Param("externalUrl") String externalUrl,
                @Param("streams") String streams,
                @Param("createdAt") LocalDateTime createdAt,
                @Param("releaseDate") String releaseDate,
                @Param("names") String names,
                @Param("waiting") int waiting);

    @Modifying
    @Query("UPDATE watchlist_item SET tmdb_id = :tmdbId, title = :title, year = :year, overview = :overview, "
            + "rating = :rating, poster_url = :posterUrl, external_url = :externalUrl, streams = :streams, "
            + "release_date = :releaseDate, names = :names, waiting = :waiting WHERE id = :id")
    void updateDetails(@Param("id") String id,
                       @Param("tmdbId") Integer tmdbId,
                       @Param("title") String title,
                       @Param("year") Integer year,
                       @Param("overview") String overview,
                       @Param("rating") Double rating,
                       @Param("posterUrl") String posterUrl,
                       @Param("externalUrl") String externalUrl,
                       @Param("streams") String streams,
                       @Param("releaseDate") String releaseDate,
                       @Param("names") String names,
                       @Param("waiting") int waiting);

    @Query("SELECT * FROM watchlist_item WHERE waiting = 1 ORDER BY created_at")
    List<WatchlistItemRecord> findWaiting();

    @Query("SELECT * FROM watchlist_item WHERE waiting = 1 AND user_name = :userName ORDER BY created_at")
    List<WatchlistItemRecord> findWaitingByUser(@Param("userName") String userName);

    @Modifying
    @Query("UPDATE watchlist_item SET checked_at = :at WHERE id = :id")
    void markChecked(@Param("id") String id, @Param("at") LocalDateTime at);

    @Modifying
    @Query("UPDATE watchlist_item SET waiting = 0, checked_at = :at, available_since = :at, available_sources = :sources, "
            + "available_query = :query, seen_at = NULL WHERE id = :id")
    void markAvailable(@Param("id") String id, @Param("at") LocalDateTime at,
                       @Param("sources") String sources, @Param("query") String query);

    @Modifying
    @Query("UPDATE watchlist_item SET seen_at = :at WHERE id = :id AND user_name = :userName AND available_since IS NOT NULL")
    int markSeen(@Param("id") String id, @Param("userName") String userName, @Param("at") LocalDateTime at);

    @Modifying
    @Query("DELETE FROM watchlist_item WHERE id = :id AND user_name = :userName")
    int deleteByIdAndUser(@Param("id") String id, @Param("userName") String userName);
}
