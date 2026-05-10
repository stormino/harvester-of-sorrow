package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.stormino.config.RaiPlayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Handles automatic RaiPlay session management via RaiSSO (Rai's own auth service,
 * not Gigya/SAP CDC).
 *
 * <p>Three-step flow, all under {@code https://www.raiplay.it/atomatic/...}:
 * <ol>
 *   <li>POST {@code /token-service/api/anonymize} with the static anonymize key
 *       → returns a per-session {@code domainApiKey}</li>
 *   <li>POST {@code /raisso-service/login/site} with {@code domainApiKey} +
 *       email + password → returns {@code authorization} (JWT) and {@code refreshToken}</li>
 *   <li>POST {@code /raisso-service/token/check} with header {@code x-auth-token}
 *       and the {@code refreshToken} → rotates the JWT</li>
 * </ol>
 *
 * <p>Authenticated requests inject the JWT and the {@code domainApiKey} via
 * {@link RaiPlayAuthInterceptor} as headers ({@code x-auth-token} +
 * {@code domainapikey}); cookies are not used.
 *
 * <p>Uses an internal {@link OkHttpClient} to avoid a circular Spring bean dependency
 * (the application's shared client depends on {@link RaiPlayAuthInterceptor}, which
 * reads the tokens produced here).
 *
 * <p>Only instantiated when {@code raiplay.username} and {@code raiplay.password} are both set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression(
        "!'${raiplay.username:}'.isEmpty() && !'${raiplay.password:}'.isEmpty()")
public class RaiPlayAuthService {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    private final RaiPlayProperties properties;

    private OkHttpClient authClient;

    private volatile String authToken    = null;
    private volatile String domainApiKey = null;
    private volatile String refreshToken = null;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    @PostConstruct
    public void init() {
        authClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .followRedirects(true)
                .build();

        log.info("RaiPlay auto-login enabled for user {}", properties.getUsername());
        refresh();
    }

    /** JWT to send as {@code x-auth-token}, refreshing if close to expiry. */
    public String getAuthToken() {
        if (authToken != null && Instant.now().isBefore(tokenExpiry.minus(1, ChronoUnit.HOURS))) {
            return authToken;
        }
        refresh();
        return authToken;
    }

    /** Per-session domain key to send as {@code domainapikey}. */
    public String getDomainApiKey() {
        if (domainApiKey == null) refresh();
        return domainApiKey;
    }

    /** Proactively refresh ahead of expiry. */
    @Scheduled(fixedDelayString = "PT23H")
    public void scheduledRefresh() {
        log.debug("RaiPlay scheduled session refresh");
        refresh();
    }

    private synchronized void refresh() {
        try {
            // If we already have a refresh token, try the cheap path first.
            if (authToken != null && refreshToken != null && tokenCheck()) {
                log.info("RaiPlay session refreshed via token/check");
                return;
            }

            String dak = anonymize();
            if (dak == null) return;
            domainApiKey = dak;

            LoginResponse login = login(dak);
            if (login == null || login.authorization() == null) return;

            authToken    = login.authorization();
            refreshToken = login.refreshToken();
            tokenExpiry  = Instant.now().plus(20, ChronoUnit.HOURS);
            log.info("RaiPlay session established successfully");
        } catch (Exception e) {
            log.error("RaiPlay auto-auth failed: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // RaiSSO API calls
    // -------------------------------------------------------------------------

    /** Step 1: exchange the static anonymize key for a per-session domainApiKey. */
    private String anonymize() throws IOException {
        String json = objectMapper.writeValueAsString(Map.of("key", properties.getAnonymizeKey()));
        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/atomatic/token-service/api/anonymize")
                .post(RequestBody.create(json, JSON_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("x-caller", "web")
                .addHeader("x-caller-version", "1.0")
                .addHeader("Origin", properties.getBaseUrl())
                .addHeader("Referer", properties.getBaseUrl() + "/")
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.error("RaiPlay anonymize HTTP {}: {}", response.code(), body);
                return null;
            }
            AnonymizeResponse parsed = objectMapper.readValue(body, AnonymizeResponse.class);
            if (parsed.domainApiKey() == null) {
                log.error("RaiPlay anonymize returned no domainApiKey: {}", body);
                return null;
            }
            return parsed.domainApiKey();
        }
    }

    /** Step 2: log in with the freshly minted domainApiKey + email + password. */
    private LoginResponse login(String dak) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("domainApiKey", dak)
                .add("email", properties.getUsername())
                .add("password", properties.getPassword())
                .build();

        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/atomatic/raisso-service/login/site")
                .post(body)
                .addHeader("Accept", "application/json")
                .addHeader("x-caller", "web")
                .addHeader("x-caller-version", "1.0")
                .addHeader("Origin", properties.getBaseUrl())
                .addHeader("Referer", properties.getBaseUrl() + "/")
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.error("RaiPlay login HTTP {}: {}", response.code(), raw);
                return null;
            }
            LoginResponse parsed = objectMapper.readValue(raw, LoginResponse.class);
            if (parsed.authorization() == null) {
                log.error("RaiPlay login returned no authorization JWT: {}", raw);
                return null;
            }
            return parsed;
        }
    }

    /** Step 3: rotate the JWT using the refreshToken. */
    private boolean tokenCheck() {
        if (domainApiKey == null || refreshToken == null) return false;
        RequestBody body = new FormBody.Builder()
                .add("domainApiKey", domainApiKey)
                .add("refreshToken", refreshToken)
                .build();

        Request request = new Request.Builder()
                .url(properties.getBaseUrl() + "/atomatic/raisso-service/token/check")
                .post(body)
                .addHeader("Accept", "application/json")
                .addHeader("x-auth-token", authToken)
                .addHeader("x-caller", "web")
                .addHeader("x-caller-version", "1.0")
                .addHeader("Origin", properties.getBaseUrl())
                .addHeader("Referer", properties.getBaseUrl() + "/")
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.warn("RaiPlay token/check HTTP {}: {} – falling back to full login",
                        response.code(), raw);
                return false;
            }
            LoginResponse parsed = objectMapper.readValue(raw, LoginResponse.class);
            if (parsed.authorization() == null) return false;
            authToken = parsed.authorization();
            if (parsed.refreshToken() != null) refreshToken = parsed.refreshToken();
            tokenExpiry = Instant.now().plus(20, ChronoUnit.HOURS);
            return true;
        } catch (IOException e) {
            log.warn("RaiPlay token/check failed: {} – falling back to full login", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Response DTOs
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnonymizeResponse(@JsonProperty("domainApiKey") String domainApiKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoginResponse(
            @JsonProperty("authorization") String authorization,
            @JsonProperty("refreshToken")  String refreshToken,
            @JsonProperty("ua")            String ua
    ) {}
}
