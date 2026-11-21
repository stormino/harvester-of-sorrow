package com.github.stormino.service;

import com.uwetrottmann.tmdb2.Tmdb;
import com.uwetrottmann.tmdb2.entities.AppendToResponse;
import com.uwetrottmann.tmdb2.entities.BaseMovie;
import com.uwetrottmann.tmdb2.entities.BaseTvShow;
import com.uwetrottmann.tmdb2.entities.Movie;
import com.uwetrottmann.tmdb2.entities.MovieResultsPage;
import com.uwetrottmann.tmdb2.entities.TvEpisode;
import com.uwetrottmann.tmdb2.entities.TvSeason;
import com.uwetrottmann.tmdb2.entities.TvShow;
import com.uwetrottmann.tmdb2.entities.TvShowResultsPage;
import com.uwetrottmann.tmdb2.enumerations.AppendToResponseItem;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.VixSrcMetadata;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class TmdbMetadataService {

    private final Tmdb tmdb;

    public TmdbMetadataService(@Nullable Tmdb tmdb) {
        this.tmdb = tmdb;
    }
    
    public boolean isAvailable() {
        return tmdb != null;
    }
    
    /**
     * Get movie metadata
     */
    public Optional<ContentMetadata> getMovieMetadata(int tmdbId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        
        try {
            Response<Movie> response = tmdb.moviesService()
                    .summary(tmdbId, null, null)
                    .execute();
            
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Failed to fetch movie metadata for ID {}: {}", tmdbId, response.code());
                return Optional.empty();
            }
            
            Movie movie = response.body();

            Integer year = null;
            if (movie != null && movie.release_date != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                year = Integer.parseInt(sdf.format(movie.release_date));
            }

            if (movie == null) {
                return Optional.empty();
            }

            return Optional.of(ContentMetadata.builder()
                    .source(MediaSource.VIXSRC)
                    .sourceMetadata(new VixSrcMetadata(tmdbId, null, null))
                    .tmdbId(tmdbId)
                    .title(movie.title)
                    .originalTitle(movie.original_title)
                    .year(year)
                    .overview(movie.overview)
                    .voteAverage(movie.vote_average)
                    .build());

        } catch (IOException e) {
            log.error("Error fetching movie metadata for ID {}: {}", tmdbId, e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Get TV show episode metadata
     */
    public Optional<ContentMetadata> getTvMetadata(int tmdbId, int season, int episode) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        
        try {
            // Get show info
            Response<TvShow> showResponse = tmdb.tvService()
                    .tv(tmdbId, null, null)
                    .execute();
            
            if (!showResponse.isSuccessful() || showResponse.body() == null) {
                log.warn("Failed to fetch TV show metadata for ID {}: {}", tmdbId, showResponse.code());
                return Optional.empty();
            }
            
            TvShow show = showResponse.body();

            if (show == null) {
                return Optional.empty();
            }

            // Get episode info
            Response<TvEpisode> episodeResponse = tmdb.tvEpisodesService()
                    .episode(tmdbId, season, episode, null, null)
                    .execute();

            String episodeName = null;
            TvEpisode tvEpisode = episodeResponse.body();
            if (episodeResponse.isSuccessful() && tvEpisode != null) {
                episodeName = tvEpisode.name;
            }

            Integer year = null;
            if (show.first_air_date != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                year = Integer.parseInt(sdf.format(show.first_air_date));
            }

            return Optional.of(ContentMetadata.builder()
                    .source(MediaSource.VIXSRC)
                    .sourceMetadata(new VixSrcMetadata(tmdbId, season, episode))
                    .tmdbId(tmdbId)
                    .title(show.name)
                    .originalTitle(show.original_name)
                    .year(year)
                    .season(season)
                    .episode(episode)
                    .episodeName(episodeName)
                    .numberOfSeasons(show.number_of_seasons)
                    .overview(show.overview)
                    .voteAverage(show.vote_average)
                    .build());
            
        } catch (IOException e) {
            log.error("Error fetching TV metadata for ID {}, S{}E{}: {}", 
                    tmdbId, season, episode, e.getMessage());
            return Optional.empty();
        }
    }
    
    private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w342";

    private static String posterUrl(String posterPath) {
        return posterPath == null || posterPath.isBlank() ? null : POSTER_BASE_URL + posterPath;
    }

    /**
     * Search for movies
     */
    public List<ContentMetadata> searchMovies(String query) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<MovieResultsPage> response = tmdb.searchService()
                    .movie(query, null, null, null, null, null, null)
                    .execute();

            MovieResultsPage page = response.body();
            if (!response.isSuccessful() || page == null || page.results == null) {
                log.warn("Movie search failed: {}", response.code());
                return List.of();
            }

            List<ContentMetadata> results = new ArrayList<>();

            for (BaseMovie movie : page.results) {
                if (movie == null) {
                    continue;
                }

                Integer year = null;
                if (movie.release_date != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                    year = Integer.parseInt(sdf.format(movie.release_date));
                }

                results.add(ContentMetadata.builder()
                        .source(MediaSource.VIXSRC)
                        .sourceMetadata(new VixSrcMetadata(movie.id, null, null))
                        .contentType(DownloadTask.ContentType.MOVIE)
                        .tmdbId(movie.id)
                        .title(movie.title)
                        .posterUrl(posterUrl(movie.poster_path))
                        .originalTitle(movie.original_title)
                        .year(year)
                        .overview(movie.overview)
                        .voteAverage(movie.vote_average)
                        .build());
            }

            return results;
            
        } catch (IOException e) {
            log.error("Error searching movies: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Titles of the same search in another language (ISO 639-1, e.g. "it"), by TMDB id: one extra
     * search call. Release names on XDCC use the language of the release ("Curioso come George" for
     * the Italian dub of "Curious George"), so the search looks them up by both names.
     */
    public Map<Integer, String> localTitles(String query, boolean tv, String language) {
        if (!isAvailable() || language == null || language.isBlank()) {
            return Map.of();
        }
        try {
            Map<Integer, String> titles = new LinkedHashMap<>();
            if (tv) {
                Response<TvShowResultsPage> response = tmdb.searchService().tv(query, null, language, null, null).execute();
                if (response.isSuccessful() && response.body() != null && response.body().results != null) {
                    response.body().results.stream().filter(s -> s != null && s.id != null && s.name != null)
                            .forEach(s -> titles.putIfAbsent(s.id, s.name));
                }
            } else {
                Response<MovieResultsPage> response = tmdb.searchService()
                        .movie(query, null, language, null, null, null, null).execute();
                if (response.isSuccessful() && response.body() != null && response.body().results != null) {
                    response.body().results.stream().filter(m -> m != null && m.id != null && m.title != null)
                            .forEach(m -> titles.putIfAbsent(m.id, m.title));
                }
            }
            return titles;
        } catch (IOException | RuntimeException e) {
            log.debug("Localized {} search for '{}' failed: {}", tv ? "TV" : "movie", query, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Search for TV shows
     */
    public List<ContentMetadata> searchTvShows(String query) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<TvShowResultsPage> response = tmdb.searchService()
                    .tv(query, null, null, null, null)
                    .execute();

            TvShowResultsPage page = response.body();
            if (!response.isSuccessful() || page == null || page.results == null) {
                log.warn("TV search failed: {}", response.code());
                return List.of();
            }

            List<ContentMetadata> results = new ArrayList<>();

            for (BaseTvShow show : page.results) {
                if (show == null) {
                    continue;
                }

                // Fetch detailed info for episode count
                try {
                    Response<TvShow> detailResponse = tmdb.tvService()
                            .tv(show.id, null, null)
                            .execute();

                    Integer year = null;
                    Integer totalEpisodes = null;
                    Integer numberOfSeasons = null;

                    TvShow detail = detailResponse.body();
                    Map<Integer, Integer> episodesPerSeason = null;
                    if (detailResponse.isSuccessful() && detail != null) {
                        if (detail.first_air_date != null) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                            year = Integer.parseInt(sdf.format(detail.first_air_date));
                        }

                        numberOfSeasons = detail.number_of_seasons;
                        totalEpisodes = detail.number_of_episodes;

                        if (detail.seasons != null) {
                            episodesPerSeason = new LinkedHashMap<>();
                            for (TvSeason s : detail.seasons) {
                                if (s.season_number != null && s.season_number > 0
                                        && s.episode_count != null) {
                                    episodesPerSeason.put(s.season_number, s.episode_count);
                                }
                            }
                        }
                    }

                    results.add(ContentMetadata.builder()
                            .source(MediaSource.VIXSRC)
                            .sourceMetadata(new VixSrcMetadata(show.id, null, null))
                            .contentType(DownloadTask.ContentType.TV)
                            .tmdbId(show.id)
                            .title(show.name)
                            .posterUrl(posterUrl(show.poster_path))
                            .originalTitle(show.original_name)
                            .year(year)
                            .numberOfSeasons(numberOfSeasons)
                            .totalEpisodes(totalEpisodes)
                            .episodesPerSeason(episodesPerSeason)
                            .overview(show.overview)
                            .voteAverage(show.vote_average)
                            .build());

                } catch (Exception e) {
                    log.debug("Failed to fetch details for show {}: {}", show.id, e.getMessage());
                }
            }

            return results;
            
        } catch (IOException e) {
            log.error("Error searching TV shows: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Get TV show basic metadata (without episode info)
     */
    public Optional<ContentMetadata> getTvShowMetadata(int tmdbId) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        try {
            Response<TvShow> showResponse = tmdb.tvService()
                    .tv(tmdbId, null, null)
                    .execute();

            if (!showResponse.isSuccessful() || showResponse.body() == null) {
                return Optional.empty();
            }

            TvShow show = showResponse.body();
            Integer year = null;
            if (show.first_air_date != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                year = Integer.parseInt(sdf.format(show.first_air_date));
            }

            return Optional.of(ContentMetadata.builder()
                    .source(MediaSource.VIXSRC)
                    .sourceMetadata(new VixSrcMetadata(tmdbId, null, null))
                    .tmdbId(tmdbId)
                    .title(show.name)
                    .originalTitle(show.original_name)
                    .year(year)
                    .numberOfSeasons(show.number_of_seasons)
                    .overview(show.overview)
                    .voteAverage(show.vote_average)
                    .build());

        } catch (IOException e) {
            log.error("Error fetching TV show metadata for ID {}: {}", tmdbId, e.getMessage());
            return Optional.empty();
        }
    }

    private final Map<String, Optional<String>> originalLanguageCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Original language of a movie or show as an ISO 639-1 code ("en"). Used to label audio
     * that comes built into the video stream, which the source doesn't name. Cached.
     */
    public Optional<String> getOriginalLanguage(int tmdbId, boolean tv) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        String key = (tv ? "tv:" : "movie:") + tmdbId;
        Optional<String> cached = originalLanguageCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            String language;
            int code;
            if (tv) {
                Response<TvShow> response = tmdb.tvService().tv(tmdbId, null, null).execute();
                code = response.code();
                language = response.isSuccessful() && response.body() != null ? response.body().original_language : null;
            } else {
                Response<Movie> response = tmdb.moviesService().summary(tmdbId, null, null).execute();
                code = response.code();
                language = response.isSuccessful() && response.body() != null ? response.body().original_language : null;
            }
            if (code >= 500) {
                return Optional.empty(); // transient: don't cache
            }
            Optional<String> result = Optional.ofNullable(language).filter(l -> !l.isBlank());
            originalLanguageCache.put(key, result);
            return result;
        } catch (IOException e) {
            log.warn("Error fetching original language for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private final Map<String, Optional<String[]>> trailerCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * YouTube trailer of a movie or show: [url, language]. Prefers an official trailer in
     * {@code preferredLanguage}, then any trailer in English or another language, then a teaser.
     * Cached.
     */
    public Optional<String[]> getTrailer(int tmdbId, boolean tv, String preferredLanguage) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        String key = (tv ? "tv:" : "movie:") + tmdbId + ":" + preferredLanguage;
        Optional<String[]> cached = trailerCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<com.uwetrottmann.tmdb2.entities.Videos.Video> videos = new ArrayList<>();
            for (String lang : new String[]{preferredLanguage, null}) {
                Response<com.uwetrottmann.tmdb2.entities.Videos> r = tv
                        ? tmdb.tvService().videos(tmdbId, lang).execute()
                        : tmdb.moviesService().videos(tmdbId, lang).execute();
                if (r.code() >= 500) {
                    return Optional.empty(); // transient: don't cache
                }
                if (r.isSuccessful() && r.body() != null && r.body().results != null) {
                    videos.addAll(r.body().results);
                }
            }
            Optional<String[]> best = videos.stream()
                    .filter(v -> "YouTube".equalsIgnoreCase(v.site) && v.key != null)
                    .min(java.util.Comparator
                            .comparingInt((com.uwetrottmann.tmdb2.entities.Videos.Video v) ->
                                    v.type == com.uwetrottmann.tmdb2.enumerations.VideoType.TRAILER ? 0
                                            : v.type == com.uwetrottmann.tmdb2.enumerations.VideoType.TEASER ? 1 : 2)
                            .thenComparingInt(v -> preferredLanguage != null && preferredLanguage.equals(v.iso_639_1) ? 0
                                    : "en".equals(v.iso_639_1) ? 1 : 2)
                            .thenComparingInt(v -> v.name != null && v.name.toLowerCase().contains("official") ? 0 : 1))
                    .map(v -> new String[]{"https://www.youtube.com/watch?v=" + v.key, v.iso_639_1});
            trailerCache.put(key, best);
            return best;
        } catch (IOException e) {
            log.warn("Error fetching trailer for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * TMDB show status: "Returning Series", "In Production", "Planned",
     * "Pilot", "Ended" or "Canceled". Empty when unavailable or on error.
     */
    public Optional<String> getTvShowStatus(int tmdbId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            Response<TvShow> response = tmdb.tvService().tv(tmdbId, null, null).execute();
            TvShow show = response.body();
            if (!response.isSuccessful() || show == null) {
                log.warn("Failed to fetch TV status for ID {}: {}", tmdbId, response.code());
                return Optional.empty();
            }
            return Optional.ofNullable(show.status).filter(st -> !st.isBlank());
        } catch (IOException e) {
            log.warn("Error fetching TV status for ID {}: {}", tmdbId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Best TMDB TV match for a title, optionally restricted to a first-air
     * year. Takes TMDB's top-ranked result. Empty when nothing matches.
     */
    public Optional<Integer> findTvShowId(String title, @Nullable Integer firstAirYear) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            Response<TvShowResultsPage> response = tmdb.searchService()
                    .tv(title, null, null, firstAirYear, null)
                    .execute();
            TvShowResultsPage page = response.body();
            if (!response.isSuccessful() || page == null || page.results == null) {
                log.warn("TV search for '{}' failed: {}", title, response.code());
                return Optional.empty();
            }
            return page.results.stream()
                    .filter(r -> r != null && r.id != null)
                    .map(r -> r.id)
                    .findFirst();
        } catch (IOException e) {
            log.warn("Error searching TV show '{}': {}", title, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all seasons for a TV show
     */
    public List<TvSeason> getSeasons(int tmdbId) {
        if (!isAvailable()) {
            return List.of();
        }

        try {
            Response<TvShow> response = tmdb.tvService()
                    .tv(tmdbId, null, new AppendToResponse(AppendToResponseItem.IMAGES))
                    .execute();

            TvShow show = response.body();
            if (!response.isSuccessful() || show == null) {
                return List.of();
            }

            // Filter out specials (season 0)
            return show.seasons != null ? show.seasons.stream()
                    .filter(season -> season.season_number > 0)
                    .toList() : List.of();

        } catch (IOException e) {
            log.error("Error fetching seasons for show {}: {}", tmdbId, e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Get all episodes for a season
     */
    public List<TvEpisode> getEpisodes(int tmdbId, int season) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<TvSeason> response = tmdb.tvSeasonsService()
                    .season(tmdbId, season, null, null)
                    .execute();

            TvSeason tvSeason = response.body();
            if (!response.isSuccessful() || tvSeason == null) {
                return List.of();
            }

            return tvSeason.episodes != null ? tvSeason.episodes : List.of();
            
        } catch (IOException e) {
            log.error("Error fetching episodes for show {}, season {}: {}", tmdbId, season, e.getMessage());
            return List.of();
        }
    }
}
