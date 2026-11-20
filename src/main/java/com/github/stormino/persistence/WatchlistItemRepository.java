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
            + "poster_url, external_url, streams, created_at) VALUES (:id, :userName, :titleKey, :contentType, :tmdbId, "
            + ":title, :year, :overview, :rating, :posterUrl, :externalUrl, :streams, :createdAt)")
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
                @Param("createdAt") LocalDateTime createdAt);

    @Modifying
    @Query("UPDATE watchlist_item SET tmdb_id = :tmdbId, title = :title, year = :year, overview = :overview, "
            + "rating = :rating, poster_url = :posterUrl, external_url = :externalUrl, streams = :streams WHERE id = :id")
    void updateDetails(@Param("id") String id,
                       @Param("tmdbId") Integer tmdbId,
                       @Param("title") String title,
                       @Param("year") Integer year,
                       @Param("overview") String overview,
                       @Param("rating") Double rating,
                       @Param("posterUrl") String posterUrl,
                       @Param("externalUrl") String externalUrl,
                       @Param("streams") String streams);

    @Modifying
    @Query("DELETE FROM watchlist_item WHERE id = :id AND user_name = :userName")
    int deleteByIdAndUser(@Param("id") String id, @Param("userName") String userName);
}
