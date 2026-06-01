package com.github.stormino.model;

import com.github.stormino.model.source.SourceMetadata;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MonitoredShow {

    private String id;
    private String title;
    private Integer year;
    private Integer tmdbId;
    private MediaSource source;
    private SourceMetadata sourceMetadata;
    /** Folder name under DOWNLOAD_TV_SHOWS_PATH, e.g. "Breaking.Bad.2008" */
    private String directoryName;
    private boolean enabled;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastNewEpisodeAt;
    private LocalDateTime createdAt;
}
