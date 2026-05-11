package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Content descriptor JSON from https://www.raiplay.it{pathId}.json (note the leading slash).
 * Field shape is taken from real fixtures (Cosmonauta, Rocco Schiavone S1E1).
 *
 * <p>Note: {@code season} and {@code episode} are JSON STRINGS in this API, not numbers,
 * and may be the empty string for films. Use the helpers below for safe access.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaiPlayContentDescriptor(
        String id,
        String type,
        String name,
        @JsonProperty("path_id") String pathId,
        String description,
        String season,
        String episode,
        @JsonProperty("episode_title") String episodeTitle,
        @JsonProperty("date_published") String datePublished,
        @JsonProperty("login_required") Boolean loginRequired,
        Video video,
        @JsonProperty("program_info") ProgramInfo programInfo,
        @JsonProperty("track_info") TrackInfo trackInfo
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Video(
            @JsonProperty("content_url") String contentUrl,
            String duration,
            @JsonProperty("subtitlesArray") List<Subtitle> subtitles
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subtitle(String language, String label, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProgramInfo(
            String id,
            String name,
            @JsonProperty("path_id") String pathId,
            String typology,
            String year
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrackInfo(
            String typology,
            String season,
            @JsonProperty("episode_number") String episodeNumber,
            @JsonProperty("publication_date") String publicationDate
    ) {}

    public boolean isFilm() {
        if (programInfo != null && "Film".equalsIgnoreCase(programInfo.typology())) {
            return true;
        }
        return isBlank(season) && isBlank(episode);
    }

    public boolean requiresLogin() {
        return loginRequired != null && loginRequired;
    }

    public Integer parseSeason() {
        return parseInt(season);
    }

    public Integer parseEpisode() {
        return parseInt(episode);
    }

    public Integer extractYear() {
        if (programInfo != null && !isBlank(programInfo.year())) {
            Integer y = parseInt(programInfo.year());
            if (y != null) return y;
        }
        // date_published is "DD-MM-YYYY"
        if (datePublished != null && datePublished.length() >= 10) {
            return parseInt(datePublished.substring(6));
        }
        return null;
    }

    public String contentUrl() {
        return video != null ? video.contentUrl() : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Integer parseInt(String s) {
        if (isBlank(s)) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
