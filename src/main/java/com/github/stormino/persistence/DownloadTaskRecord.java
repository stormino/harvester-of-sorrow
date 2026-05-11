package com.github.stormino.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("download_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTaskRecord {

    @Id
    private String id;
    private String source;
    private String sourceMetadata;   // JSON, polymorphic via Jackson
    private String contentType;
    private Integer tmdbId;
    private Integer season;
    private Integer episode;
    private String title;
    private String episodeName;
    private Integer year;
    private String languages;    // comma-separated: "en,it"
    private String quality;
    private String outputPath;
    private String playlistUrl;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
