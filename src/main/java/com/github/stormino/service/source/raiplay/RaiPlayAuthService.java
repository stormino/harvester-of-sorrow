package com.github.stormino.service.source.raiplay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import com.github.stormino.config.RaiPlayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
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

/**
 * Handles automatic RaiPlay session management via RaiSSO.
 *
 * <p>Two-step flow under {@code https://www.raiplay.it/atomatic/}:
 * <ol>
 *   <li>POST {@code /raisso-service/login/site} with the static {@code domainApiKey}
 *       + email + password → returns {@code authorization} (JWT) and {@code refreshToken}</li>
 *   <li>POST {@code /raisso-service/token/check} with header {@code x-auth-token}
 *       and the {@code refreshToken} → rotates the JWT cheaply</li>
 * </ol>
 *
 * <p>{@code domainApiKey} is a static string embedded in the RaiPlay website JS
 * ({@code arSgRtwasD324SaA}); it is NOT obtained dynamically.
 *
 * <p>Authenticated requests inject the JWT and the static key via
 * {@link RaiPlayAuthInterceptor} as headers ({@code x-auth-token} +
 * {@code domainapikey}).
 *
 * <p>Uses an internal {@link OkHttpClient} to avoid a circular Spring bean dependency
 * (the application's shared client depends on {@link RaiPlayAuthInterceptor}).
 *
 * <p>Only instantiated when {@code raiplay.username} and {@code raiplay.password} are both set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression(
        "!'${raiplay.username:}'.isEmpty() && !'${raiplay.password:}'.isEmpty()")
public class RaiPlayAuthService {

    private final ObjectMapper objectMapper;
    private final RaiPlayProperties properties;

    private OkHttpClient authClient;

    private volatile String authToken    = null;
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

    /** Static frontend key to send as {@code domainapikey}. */
    public String getDomainApiKey() {
        return properties.getDomainApiKey();
    }

    @Scheduled(fixedDelayString = "PT23H")
    public void scheduledRefresh() {
        log.debug("RaiPlay scheduled session refresh");
        refresh();
    }

    private synchronized void refresh() {
        try {
            if (authToken != null && refreshToken != null && tokenCheck()) {
                log.info("RaiPlay session refreshed via token/check");
                return;
            }
            fullLogin();
        } catch (Exception e) {
            log.error("RaiPlay auto-auth failed: {}", e.getMessage(), e);
        }
    }

    private void fullLogin() throws IOException {
        LoginResponse login = callLogin();
        if (login == null || login.authorization() == null) return;
        authToken    = login.authorization();
        refreshToken = login.refreshToken();
        tokenExpiry  = Instant.now().plus(20, ChronoUnit.HOURS);
        log.info("RaiPlay session established successfully");
    }

    // -------------------------------------------------------------------------
    // RaiSSO API calls
    // -------------------------------------------------------------------------

    private LoginResponse callLogin() throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("domainApiKey", properties.getDomainApiKey())
                .add("email",        properties.getUsername())
                .add("password",     properties.getPassword())
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

    private boolean tokenCheck() {
        RequestBody body = new FormBody.Builder()
                .add("domainApiKey",  properties.getDomainApiKey())
                .add("refreshToken",  refreshToken)
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
    record LoginResponse(
            @JsonProperty("authorization") String authorization,
            @JsonProperty("refreshToken")  String refreshToken,
            @JsonProperty("ua")            String ua
    ) {}
}
