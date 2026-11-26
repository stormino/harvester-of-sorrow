package com.github.stormino.service;

import com.github.stormino.config.AppProperties;
import com.github.stormino.model.PlaylistInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class VixSrcExtractorService {
    
    private final OkHttpClient httpClient;
    private final AppProperties properties;
    
    // Regex patterns (ported from Python)
    private static final Pattern MASTER_PLAYLIST_PATTERN = 
            Pattern.compile("window\\.masterPlaylist\\s*=\\s*\\{[^}]*\\{[^}]*\\}[^}]*\\}");
    
    private static final Pattern PLAYLIST_DIRECT_PATTERN = 
            Pattern.compile("https://vixsrc\\.to/playlist/(\\d+)\\?[^\"']*");
    
    private static final Pattern API_ENDPOINTS_PATTERN = 
            Pattern.compile("[\"']([/]api/[^\"']+)[\"']");
    
    private static final Pattern VIDEO_ID_PATTERN =
            Pattern.compile("video[_-]?id[\"']?\\s*[:=]\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EMBED_SRC_PATTERN =
            Pattern.compile("\"src\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern CAN_PLAY_FHD_PATTERN =
            Pattern.compile("window\\.canPlayFHD\\s*=\\s*(true|false)");

    /**
     * Get playlist URL for a movie
     */
    public Optional<PlaylistInfo> getMoviePlaylist(int tmdbId, String language) {
        String base = properties.getExtractor().getBaseUrl();
        String apiUrl = String.format("%s/api/movie/%d", base, tmdbId);
        String pageUrl = String.format("%s/movie/%d/", base, tmdbId);
        return fetchEmbedUrl(apiUrl, language)
                .flatMap(embedUrl -> extractPlaylistUrl(embedUrl, language, pageUrl));
    }

    /**
     * Get playlist URL for a TV episode
     */
    public Optional<PlaylistInfo> getTvPlaylist(int tmdbId, int season, int episode, String language) {
        String base = properties.getExtractor().getBaseUrl();
        String apiUrl = String.format("%s/api/tv/%d/%d/%d", base, tmdbId, season, episode);
        String pageUrl = String.format("%s/tv/%d/%d/%d/", base, tmdbId, season, episode);
        return fetchEmbedUrl(apiUrl, language)
                .flatMap(embedUrl -> extractPlaylistUrl(embedUrl, language, pageUrl));
    }

    /**
     * Call the vixsrc API to get the embed URL for a given content API endpoint.
     * The API returns JSON like: {"src": "/embed/696101?token=...&lang=en&..."}
     */
    private Optional<String> fetchEmbedUrl(String apiUrl, String language) {
        log.debug("Fetching embed src from API: {}", apiUrl);
        try {
            Request request = new Request.Builder().url(apiUrl).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("API call failed for {}: {}", apiUrl, response.code());
                    return Optional.empty();
                }
                String json = response.body().string();
                Matcher matcher = EMBED_SRC_PATTERN.matcher(json);
                if (!matcher.find()) {
                    log.warn("No 'src' field in API response from {}: {}", apiUrl, json);
                    return Optional.empty();
                }
                String src = matcher.group(1).replace("\\/", "/");
                // Prepend base URL if relative, then override language
                String baseUrl = properties.getExtractor().getBaseUrl();
                String embedUrl = src.startsWith("http") ? src : baseUrl + src;
                embedUrl = overrideLang(embedUrl, language);
                log.debug("Resolved embed URL: {}", embedUrl);
                return Optional.of(embedUrl);
            }
        } catch (IOException e) {
            log.error("Error calling API {}: {}", apiUrl, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private String overrideLang(String url, String language) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return url;
        return parsed.newBuilder()
                .removeAllQueryParameters("lang")
                .addQueryParameter("lang", language)
                .build().toString();
    }
    
    /**
     * Extract playlist URL from embed page
     */
    public Optional<PlaylistInfo> extractPlaylistUrl(String embedUrl, String language) {
        return extractPlaylistUrl(embedUrl, language, null);
    }

    /**
     * Extract playlist URL from embed page. VixSrc answers 403 for the embed page unless
     * the request carries the title page as Referer, as a browser loading the iframe does.
     */
    public Optional<PlaylistInfo> extractPlaylistUrl(String embedUrl, String language, String pageUrl) {
        log.debug("Extracting playlist URL from: {}", embedUrl);

        try {
            String htmlContent = fetchEmbedPage(embedUrl, pageUrl);
            // Try multiple extraction strategies
            boolean canPlayFhd = canPlayFhd(htmlContent);
            Optional<String> playlistUrl = extractFromMasterPlaylist(htmlContent, language, canPlayFhd)
                    .or(() -> extractFromDirectPattern(htmlContent))
                    .or(() -> extractFromApiEndpoints(htmlContent, embedUrl))
                    .or(() -> extractFromVideoId(htmlContent));
            
            if (playlistUrl.isPresent()) {
                String url = playlistUrl.get();
                boolean verified = verifyPlaylist(url, embedUrl);
                
                return Optional.of(PlaylistInfo.builder()
                        .url(url)
                        .language(language)
                        .verified(verified)
                        .referer(embedUrl)
                        .build());
            }
            
            log.warn("Could not extract playlist URL from embed page");
            return Optional.empty();
            
        } catch (IOException e) {
            log.error("Error extracting playlist URL from {}: {}", embedUrl, e.getMessage(), e);
            return Optional.empty();
        }
    }
    
    private String fetchEmbedPage(String embedUrl, String pageUrl) throws IOException {
        Request.Builder builder = new Request.Builder().url(embedUrl);
        if (pageUrl != null) {
            builder.header("Referer", pageUrl);
        }
        Request request = builder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("Empty response body");
            }

            return response.body().string();
        }
    }
    
    /**
     * Strategy 1: Extract from window.masterPlaylist object
     */
    private Optional<String> extractFromMasterPlaylist(String html, String language, boolean canPlayFhd) {
        Matcher matcher = MASTER_PLAYLIST_PATTERN.matcher(html);
        
        if (!matcher.find()) {
            return Optional.empty();
        }
        
        String section = matcher.group(0);
        
        // Extract components
        String url = extractValue(section, "url:\\s*['\"]([^'\"]+)['\"]");
        String token = extractValue(section, "['\"]token['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
        String expires = extractValue(section, "['\"]expires['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
        
        if (url == null || token == null || expires == null) {
            return Optional.empty();
        }
        
        // Build complete URL
        String playlistUrl = buildPlaylistUrl(url, token, expires, language, section, canPlayFhd);
        log.debug("Extracted from masterPlaylist: {}", playlistUrl);
        
        return Optional.of(playlistUrl);
    }
    
    /**
     * Strategy 2: Direct regex pattern match
     */
    private Optional<String> extractFromDirectPattern(String html) {
        Matcher matcher = PLAYLIST_DIRECT_PATTERN.matcher(html);
        
        if (matcher.find()) {
            String url = matcher.group(0).replace("&amp;", "&");
            log.debug("Extracted from direct pattern: {}", url);
            return Optional.of(url);
        }
        
        return Optional.empty();
    }
    
    /**
     * Strategy 3: Try API endpoints found in JavaScript
     */
    private Optional<String> extractFromApiEndpoints(String html, String embedUrl) {
        Matcher matcher = API_ENDPOINTS_PATTERN.matcher(html);
        
        while (matcher.find()) {
            String apiPath = matcher.group(1);
            String apiUrl = properties.getExtractor().getBaseUrl() + apiPath;
            
            try {
                Request request = new Request.Builder().url(apiUrl).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (body.contains("m3u8") || body.contains("playlist")) {
                            log.debug("Extracted from API endpoint: {}", apiUrl);
                            return Optional.of(apiUrl);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("API endpoint failed: {}", apiUrl);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Strategy 4: Extract video ID and try common patterns
     */
    private Optional<String> extractFromVideoId(String html) {
        Matcher matcher = VIDEO_ID_PATTERN.matcher(html);
        
        if (!matcher.find()) {
            return Optional.empty();
        }
        
        String videoId = matcher.group(1);
        String baseUrl = properties.getExtractor().getBaseUrl();
        
        String[] patterns = {
            baseUrl + "/playlist/" + videoId,
            baseUrl + "/api/playlist/" + videoId
        };
        
        for (String testUrl : patterns) {
            try {
                Request request = new Request.Builder().url(testUrl).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (body.contains("m3u8") || response.request().url().toString().contains("playlist")) {
                            String finalUrl = response.request().url().toString();
                            log.debug("Extracted from video ID: {}", finalUrl);
                            return Optional.of(finalUrl);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Video ID pattern failed: {}", testUrl);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * The embed page sets {@code window.canPlayFHD}; the player only adds {@code h=1} (Full HD) when
     * it is true, and VixSrc answers 403 for {@code h=1} otherwise.
     */
    static boolean canPlayFhd(String html) {
        Matcher m = CAN_PLAY_FHD_PATTERN.matcher(html);
        return m.find() && "true".equals(m.group(1));
    }

    String buildPlaylistUrl(String base, String token, String expires, String language, String section,
                            boolean canPlayFhd) {
        // Extract ASN if present
        String asn = extractValue(section, "['\"]asn['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");

        HttpUrl parsedUrl = HttpUrl.parse(base);
        if (parsedUrl == null) {
            log.warn("Failed to parse base URL: {}", base);
            return base + "?token=" + token + "&expires=" + expires + (canPlayFhd ? "&h=1" : "") + "&lang=" + language;
        }

        HttpUrl.Builder urlBuilder = parsedUrl.newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("expires", expires);

        if (asn != null && !asn.isEmpty()) {
            urlBuilder.addQueryParameter("asn", asn);
        }

        if (canPlayFhd) {
            urlBuilder.addQueryParameter("h", "1");
        }
        urlBuilder.addQueryParameter("lang", language);

        return urlBuilder.build().toString().replace("&amp;", "&");
    }
    
    private boolean verifyPlaylist(String playlistUrl, String referer) {
        try {
            Request request = new Request.Builder()
                    .url(playlistUrl)
                    .header("Referer", referer)
                    .header("Accept", "*/*")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    if (body.startsWith("#EXTM3U")) {
                        log.debug("Playlist URL verified successfully");
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Playlist verification failed: {}", e.getMessage());
        }
        
        return false;
    }
    
    private String extractValue(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
    

}
