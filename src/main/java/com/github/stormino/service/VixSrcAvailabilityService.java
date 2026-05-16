package com.github.stormino.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.AvailabilityResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Determines whether a TMDB id is currently served by VixSrc.
 *
 * <p>VixSrc's per-content endpoints (e.g. {@code /movie/{tmdbId}}) now return
 * 200 even for content that isn't actually available, so the previous HEAD-
 * based probe is unreliable.  Instead we hit the list endpoint
 * {@code /api/list/{type}?lang={lang}}, which returns the full set of
 * {@code {tmdb_id, imdb_id}} entries currently online.  Membership in that
 * list is the authoritative signal.
 *
 * <p>Lists are cached per {@code (type, language)} for {@link #CACHE_TTL}
 * to avoid re-fetching them on every search result.
 */
@Slf4j
@Service
public class VixSrcAvailabilityService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final OkHttpClient httpClient;
    private final VixSrcProperties properties;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<CacheKey, CachedList> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, Object>     fetchLocks = new ConcurrentHashMap<>();

    public VixSrcAvailabilityService(OkHttpClient httpClient,
                                     VixSrcProperties properties,
                                     ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AvailabilityResult checkMovieAvailability(int tmdbId, Set<String> languages) {
        return check("movie", tmdbId, languages);
    }

    public AvailabilityResult checkTvAvailability(int tmdbId, Set<String> languages) {
        return check("tv", tmdbId, languages);
    }

    private AvailabilityResult check(String type, int tmdbId, Set<String> languages) {
        Set<String> available = new HashSet<>();
        for (String language : languages) {
            Set<Integer> ids = getOrFetch(type, language);
            if (ids.contains(tmdbId)) {
                available.add(language);
            }
        }
        return AvailabilityResult.builder()
                .available(!available.isEmpty())
                .availableLanguages(available)
                .build();
    }

    private Set<Integer> getOrFetch(String type, String language) {
        CacheKey key = new CacheKey(type, language);
        CachedList cached = cache.get(key);
        if (cached != null && !isExpired(cached)) {
            return cached.tmdbIds();
        }
        // Per-key lock: if N threads race here simultaneously, only the first
        // actually fetches; the rest wait, then find a fresh entry in the cache.
        synchronized (fetchLocks.computeIfAbsent(key, k -> new Object())) {
            cached = cache.get(key);
            if (cached != null && !isExpired(cached)) {
                return cached.tmdbIds();
            }
            Set<Integer> fresh = fetch(type, language);
            if (!fresh.isEmpty()) {
                cache.put(key, new CachedList(fresh, Instant.now()));
            }
            return fresh;
        }
    }

    private boolean isExpired(CachedList cached) {
        return Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) >= 0;
    }

    private Set<Integer> fetch(String type, String language) {
        String url = String.format("%s/api/list/%s?lang=%s",
                properties.getExtractor().getBaseUrl(), type, language);
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("VixSrc list API returned HTTP {} for {}", response.code(), url);
                return Set.of();
            }
            String body = response.body() != null ? response.body().string() : "[]";
            List<ListEntry> entries = objectMapper.readValue(body, new TypeReference<>() {});
            Set<Integer> ids = entries.stream()
                    .map(ListEntry::tmdbId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            log.info("VixSrc {} list (lang={}): {} entries", type, language, ids.size());
            return ids;
        } catch (Exception e) {
            log.error("Failed to fetch VixSrc list type={} lang={}: {}", type, language, e.getMessage());
            return Set.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ListEntry(@JsonProperty("tmdb_id") Integer tmdbId,
                             @JsonProperty("imdb_id") String imdbId) {}

    private record CacheKey(String type, String language) {}

    private record CachedList(Set<Integer> tmdbIds, Instant fetchedAt) {}
}
