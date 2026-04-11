package com.github.stormino.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface DownloadTaskRepository extends CrudRepository<DownloadTaskRecord, String> {

    @Modifying
    @Query("INSERT INTO download_task (id, content_type, tmdb_id, season, episode, title, episode_name, " +
           "year, languages, quality, output_path, playlist_url, status, error_message, created_at, started_at, completed_at) " +
           "VALUES (:id, :contentType, :tmdbId, :season, :episode, :title, :episodeName, " +
           ":year, :languages, :quality, :outputPath, :playlistUrl, :status, :errorMessage, :createdAt, :startedAt, :completedAt)")
    void insert(@Param("id") String id,
                @Param("contentType") String contentType,
                @Param("tmdbId") Integer tmdbId,
                @Param("season") Integer season,
                @Param("episode") Integer episode,
                @Param("title") String title,
                @Param("episodeName") String episodeName,
                @Param("year") Integer year,
                @Param("languages") String languages,
                @Param("quality") String quality,
                @Param("outputPath") String outputPath,
                @Param("playlistUrl") String playlistUrl,
                @Param("status") String status,
                @Param("errorMessage") String errorMessage,
                @Param("createdAt") LocalDateTime createdAt,
                @Param("startedAt") LocalDateTime startedAt,
                @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Query("UPDATE download_task SET status = :status, error_message = :errorMessage, " +
           "started_at = :startedAt, completed_at = :completedAt WHERE id = :id")
    void updateStatus(@Param("id") String id,
                      @Param("status") String status,
                      @Param("errorMessage") String errorMessage,
                      @Param("startedAt") LocalDateTime startedAt,
                      @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Query("UPDATE download_task SET playlist_url = :playlistUrl WHERE id = :id")
    void updatePlaylistUrl(@Param("id") String id, @Param("playlistUrl") String playlistUrl);

    @Modifying
    @Query("DELETE FROM download_task WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'NOT_FOUND')")
    void deleteCompleted();
}
