package com.github.stormino.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MonitoredShowRepository extends CrudRepository<MonitoredShowRecord, String> {

    @Query("SELECT * FROM monitored_show WHERE enabled = 1")
    List<MonitoredShowRecord> findAllEnabled();

    @Modifying
    @Query("INSERT INTO monitored_show (id, title, year, tmdb_id, source, source_metadata, directory_name, enabled, last_checked_at, last_new_episode_at, created_at) " +
           "VALUES (:id, :title, :year, :tmdbId, :source, :sourceMetadata, :directoryName, :enabled, :lastCheckedAt, :lastNewEpisodeAt, :createdAt)")
    void insert(@Param("id") String id,
                @Param("title") String title,
                @Param("year") Integer year,
                @Param("tmdbId") Integer tmdbId,
                @Param("source") String source,
                @Param("sourceMetadata") String sourceMetadata,
                @Param("directoryName") String directoryName,
                @Param("enabled") int enabled,
                @Param("lastCheckedAt") LocalDateTime lastCheckedAt,
                @Param("lastNewEpisodeAt") LocalDateTime lastNewEpisodeAt,
                @Param("createdAt") LocalDateTime createdAt);

    @Modifying
    @Query("UPDATE monitored_show SET enabled = :enabled WHERE id = :id")
    void updateEnabled(@Param("id") String id, @Param("enabled") int enabled);

    @Modifying
    @Query("UPDATE monitored_show SET title = :title, year = :year, tmdb_id = :tmdbId, source = :source, source_metadata = :sourceMetadata WHERE id = :id")
    void updateSourceConfig(@Param("id") String id,
                            @Param("title") String title,
                            @Param("year") Integer year,
                            @Param("tmdbId") Integer tmdbId,
                            @Param("source") String source,
                            @Param("sourceMetadata") String sourceMetadata);

    @Modifying
    @Query("UPDATE monitored_show SET last_checked_at = :lastCheckedAt, last_new_episode_at = :lastNewEpisodeAt WHERE id = :id")
    void updateCheckTimestamps(@Param("id") String id,
                               @Param("lastCheckedAt") LocalDateTime lastCheckedAt,
                               @Param("lastNewEpisodeAt") LocalDateTime lastNewEpisodeAt);
}
