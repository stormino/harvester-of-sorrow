package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.stormino.config.RaiPlayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RaiPlayApiClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RaiPlayProperties properties;

    public Optional<RaiPlaySearchResponse> search(String query, int size) {
        HttpUrl url = HttpUrl.parse(properties.getSearchUrl()).newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("pl", "raiplay")
                .addQueryParameter("size", String.valueOf(size))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Referer", properties.getBaseUrl())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("RaiPlay search HTTP {}", response.code());
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(response.body().string(), RaiPlaySearchResponse.class));
        } catch (IOException e) {
            log.error("RaiPlay search failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<RaiPlayContentDescriptor> getContentDescriptor(String pathId) {
        String url = properties.getBaseUrl() + "/" + pathId + ".json";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Referer", properties.getBaseUrl() + "/" + pathId)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("RaiPlay descriptor HTTP {} for pathId={}", response.code(), pathId);
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(response.body().string(), RaiPlayContentDescriptor.class));
        } catch (IOException e) {
            log.error("RaiPlay descriptor fetch failed for pathId={}: {}", pathId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Ensures the content_url from the descriptor has output=64 for HLS delivery.
     * RaiPlay's relinker URL typically accepts this parameter to return a master m3u8.
     */
    public String buildHlsUrl(String contentUrl) {
        if (contentUrl == null) return null;
        if (contentUrl.contains("output=")) {
            return contentUrl.replaceAll("output=\\d+", "output=64");
        }
        return contentUrl + (contentUrl.contains("?") ? "&" : "?") + "output=64";
    }
}
