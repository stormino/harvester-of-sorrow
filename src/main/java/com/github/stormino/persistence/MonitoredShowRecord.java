package com.github.stormino.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("monitored_show")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonitoredShowRecord {

    @Id
    private String id;
    private String title;
    private Integer year;
    private Integer tmdbId;
    private String source;
    private String sourceMetadata;   // JSON, polymorphic via Jackson
    private String directoryName;
    private int enabled;             // SQLite boolean: 1/0
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastNewEpisodeAt;
    private LocalDateTime createdAt;
}
