package com.github.stormino.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("download_sub_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadSubTaskRecord {

    @Id
    private String id;
    private String parentTaskId;
    private String type;         // VIDEO, AUDIO, SUBTITLE
    private String language;
    private String title;
    private String codec;
    private String resolution;
    private Long bitrate;
    private String tempFilePath;
    private String playlistUrl;
    private String status;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
