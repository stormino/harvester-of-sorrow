package com.github.stormino.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DownloadSubTaskRepository extends CrudRepository<DownloadSubTaskRecord, String> {

    List<DownloadSubTaskRecord> findAllByParentTaskId(String parentTaskId);

    @Modifying
    @Query("DELETE FROM download_sub_task WHERE parent_task_id = :parentTaskId")
    void deleteByParentTaskId(@Param("parentTaskId") String parentTaskId);

    @Modifying
    @Query("INSERT INTO download_sub_task (id, parent_task_id, type, language, title, codec, resolution, " +
           "bitrate, temp_file_path, playlist_url, status, error_message, started_at, completed_at) " +
           "VALUES (:id, :parentTaskId, :type, :language, :title, :codec, :resolution, " +
           ":bitrate, :tempFilePath, :playlistUrl, :status, :errorMessage, :startedAt, :completedAt)")
    void insert(@Param("id") String id,
                @Param("parentTaskId") String parentTaskId,
                @Param("type") String type,
                @Param("language") String language,
                @Param("title") String title,
                @Param("codec") String codec,
                @Param("resolution") String resolution,
                @Param("bitrate") Long bitrate,
                @Param("tempFilePath") String tempFilePath,
                @Param("playlistUrl") String playlistUrl,
                @Param("status") String status,
                @Param("errorMessage") String errorMessage,
                @Param("startedAt") LocalDateTime startedAt,
                @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Query("UPDATE download_sub_task SET status = :status, error_message = :errorMessage, " +
           "started_at = :startedAt, completed_at = :completedAt WHERE id = :id")
    void updateStatus(@Param("id") String id,
                      @Param("status") String status,
                      @Param("errorMessage") String errorMessage,
                      @Param("startedAt") LocalDateTime startedAt,
                      @Param("completedAt") LocalDateTime completedAt);
}
