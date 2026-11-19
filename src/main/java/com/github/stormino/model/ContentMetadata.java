package com.github.stormino.model;

import com.github.stormino.model.source.SourceMetadata;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ContentMetadata {

    @Builder.Default
    private MediaSource source = MediaSource.VIXSRC;
    private SourceMetadata sourceMetadata;

    /** MOVIE or TV; set by every provider at search time. */
    private DownloadTask.ContentType contentType;

    private Integer tmdbId;
    private String title;
    private String originalTitle;
    private Integer year;
    private String overview;
    private Double voteAverage;
    /** Absolute poster image URL, when the source provides one. */
    private String posterUrl;
    
    // TV-specific
    private Integer season;
    private Integer episode;
    private String episodeName;
    private Integer numberOfSeasons;
    private Integer totalEpisodes;
    /** Maps season number → episode count; populated at search time from TMDB. */
    private Map<Integer, Integer> episodesPerSeason;

    public String generateFilename(String language, String extension) {
        StringBuilder filename = new StringBuilder();
        
        String cleanTitle = sanitizeFilename(title != null ? title : "Unknown");
        filename.append(cleanTitle.replace(" ", "."));
        
        if (season != null && episode != null) {
            // TV format: Show.S01E01.EpisodeName.mkv
            filename.append(String.format(".S%02dE%02d", season, episode));
            if (episodeName != null && !episodeName.isBlank()) {
                String cleanEpisode = sanitizeFilename(episodeName);
                filename.append(".").append(cleanEpisode.replace(" ", "."));
            }
        } else if (year != null) {
            // Movie format: Title.2023.mkv
            filename.append(".").append(year);
        }
        
        filename.append(".").append(extension);
        return filename.toString();
    }
    
    private String sanitizeFilename(String filename) {
        return filename
                .replaceAll("[<>:\"/\\\\|?*]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
