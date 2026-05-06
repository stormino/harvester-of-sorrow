package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.stormino.config.RaiPlayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression(
        "!'${raiplay.username:}'.isEmpty() && !'${raiplay.password:}'.isEmpty()")
public class RaiPlayApiClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RaiPlayProperties properties;

    public Optional<RaiPlaySearchResponse> search(String query, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("param", query);
        params.put("from", 0);
        params.put("sort", "relevance");
        params.put("size", size);
        params.put("additionalSize", 27);
        params.put("onlyVideoQuery", false);
        params.put("onlyProgramsQuery", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateIn", properties.getSearchTemplateIn());
        body.put("templateOut", properties.getSearchTemplateOut());
        body.put("params", params);

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            log.error("Failed to serialize RaiPlay search body: {}", e.getMessage());
            return Optional.empty();
        }

        Request request = new Request.Builder()
                .url(properties.getSearchUrl())
                .post(RequestBody.create(json, JSON))
                .addHeader("Accept", "*/*")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", properties.getBaseUrl())
                .addHeader("Referer", properties.getBaseUrl() + "/")
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

    /**
     * Fetch the content descriptor JSON for a given path.
     * @param pathId leading-slash path like "/video/2018/12/COSMONAUTA-...json"
     */
    public Optional<RaiPlayContentDescriptor> getContentDescriptor(String pathId) {
        String normalized = pathId.startsWith("/") ? pathId : "/" + pathId;
        String url = properties.getBaseUrl() + normalized;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Referer", properties.getBaseUrl() + stripJsonSuffix(normalized))
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

    private static String stripJsonSuffix(String path) {
        return path.endsWith(".json") ? path.substring(0, path.length() - 5) : path;
    }
}
