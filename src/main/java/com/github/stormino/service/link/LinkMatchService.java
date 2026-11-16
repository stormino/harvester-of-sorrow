package com.github.stormino.service.link;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.TmdbMetadataService;
import com.uwetrottmann.tmdb2.entities.TvEpisode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** TMDB lookups for link downloads: candidates for a filename guess, and episode names. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkMatchService {

    private static final int MAX_CANDIDATES = 8;

    private final TmdbMetadataService tmdb;

    public boolean isAvailable() {
        return tmdb.isAvailable();
    }

    /** TMDB titles of {@code type} for {@code query}, best matches (same title, same year) first. */
    public List<ContentMetadata> search(String query, DownloadTask.ContentType type, Integer year) {
        if (query == null || query.isBlank() || !tmdb.isAvailable()) {
            return List.of();
        }
        List<ContentMetadata> results = type == DownloadTask.ContentType.TV
                ? tmdb.searchTvShows(query) : tmdb.searchMovies(query);
        String wanted = normalize(query);
        List<ContentMetadata> ranked = new ArrayList<>(results);
        ranked.sort(Comparator.comparingInt((ContentMetadata c) -> score(c, wanted, year)).reversed());
        return ranked.stream().limit(MAX_CANDIDATES).toList();
    }

    /**
     * Candidates for a guess: its own type first; when that finds nothing, the other type (a
     * "movie" guess may be a show whose files lack episode markers).
     */
    public List<ContentMetadata> candidates(ReleaseNameParser.Guess guess) {
        if (guess.title() == null || guess.title().isBlank()) {
            return List.of();
        }
        List<ContentMetadata> found = search(guess.title(), guess.type(), guess.year());
        if (found.isEmpty()) {
            DownloadTask.ContentType other = guess.type() == DownloadTask.ContentType.TV
                    ? DownloadTask.ContentType.MOVIE : DownloadTask.ContentType.TV;
            found = search(guess.title(), other, guess.year());
        }
        return found;
    }

    /** Episode names of one season of a TMDB show, by episode number (memoised by the caller). */
    public Map<Integer, String> episodeNames(int tmdbId, int season) {
        Map<Integer, String> names = new HashMap<>();
        try {
            for (TvEpisode e : tmdb.getEpisodes(tmdbId, season)) {
                if (e != null && e.episode_number != null && e.name != null && !e.name.isBlank()) {
                    names.put(e.episode_number, e.name);
                }
            }
        } catch (RuntimeException e) {
            log.debug("No TMDB episodes for {} season {}: {}", tmdbId, season, e.getMessage());
        }
        return names;
    }

    public Optional<ContentMetadata> details(int tmdbId, DownloadTask.ContentType type) {
        return type == DownloadTask.ContentType.TV ? tmdb.getTvShowMetadata(tmdbId) : tmdb.getMovieMetadata(tmdbId);
    }

    private static int score(ContentMetadata c, String wanted, Integer year) {
        int s = 0;
        if (wanted.equals(normalize(c.getTitle())) || wanted.equals(normalize(c.getOriginalTitle()))) {
            s += 10;
        }
        if (year != null && c.getYear() != null) {
            int d = Math.abs(year - c.getYear());
            s += d == 0 ? 5 : d == 1 ? 2 : 0;
        }
        return s;
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
