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
import java.util.List;
import java.util.Map;

/**
 * Handles automatic RaiPlay session management via Gigya (SAP Customer Data Cloud).
 *
 * <p>Flow:
 * <ol>
 *   <li>POST {@code accounts.login} to Gigya with email + password → session token</li>
 *   <li>POST {@code accounts.getJWT} to Gigya with the session token → ID token (JWT)</li>
 *   <li>POST the JWT to the RaiPlay social-login endpoint → {@code Set-Cookie} headers</li>
 * </ol>
 *
 * <p>Uses its own internal {@link OkHttpClient} to avoid a circular Spring bean dependency
 * (the application's shared client depends on {@link RaiPlayCookieInterceptor}, which reads
 * the cookie produced here).
 *
 * <p>Only instantiated when {@code raiplay.username} and {@code raiplay.password} are both set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression(
        "!'${raiplay.username:}'.isEmpty() && !'${raiplay.password:}'.isEmpty()")
public class RaiPlayAuthService {

    private static final String GIGYA_LOGIN_URL = "https://accounts.eu1.gigya.com/accounts.login";
    private static final String GIGYA_JWT_URL   = "https://accounts.eu1.gigya.com/accounts.getJWT";
    private static final MediaType JSON_TYPE     = MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    private final RaiPlayProperties properties;

    // Internal client: no circular dependency with the application OkHttpClient bean.
    private OkHttpClient authClient;

    private volatile String activeCookie = null;
    private volatile Instant cookieExpiry = Instant.EPOCH;

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

    /** Returns the active auto-obtained cookie, or {@code null} if login failed. */
    public String getCookie() {
        if (activeCookie != null && Instant.now().isBefore(cookieExpiry.minus(1, ChronoUnit.HOURS))) {
            return activeCookie;
        }
        refresh();
        return activeCookie;
    }

    /** Proactively refresh 1 hour before expiry (checked every 23 h). */
    @Scheduled(fixedDelayString = "PT23H")
    public void scheduledRefresh() {
        log.debug("RaiPlay scheduled session refresh");
        refresh();
    }

    private synchronized void refresh() {
        try {
            GigyaSession session = gigyaLogin();
            if (session == null) return;

            String jwt = gigyaGetJwt(session);
            if (jwt == null) return;

            String cookie = exchangeForRaiPlayCookie(jwt);
            if (cookie != null) {
                activeCookie = cookie;
                // Gigya default session expiry is 30 days; we refresh 23 h before that.
                cookieExpiry = Instant.now().plus(29, ChronoUnit.DAYS);
                log.info("RaiPlay session refreshed successfully");
            } else {
                log.warn("RaiPlay JWT exchange returned no cookies. "
                        + "If auto-login keeps failing, check raiplay.gigya-exchange-url in application.yml "
                        + "by inspecting the POST request made to raiplay.it during browser login.");
            }
        } catch (Exception e) {
            log.error("RaiPlay auto-auth failed: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Gigya API calls
    // -------------------------------------------------------------------------

    private GigyaSession gigyaLogin() throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("loginID", properties.getUsername())
                .add("password", properties.getPassword())
                .add("apiKey", properties.getGigyaApiKey())
                .add("format", "json")
                .add("include", "profile,data,loginIDs,sessionInfo")
                .build();

        Request request = new Request.Builder()
                .url(GIGYA_LOGIN_URL)
                .post(body)
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            String rawBody = response.body() != null ? response.body().string() : "{}";
            GigyaLoginResponse parsed = objectMapper.readValue(rawBody, GigyaLoginResponse.class);

            if (parsed.errorCode() != 0) {
                log.error("Gigya login error {} – {}", parsed.errorCode(), parsed.errorMessage());
                return null;
            }
            if (parsed.sessionInfo() == null) {
                log.error("Gigya login returned no sessionInfo");
                return null;
            }
            log.debug("Gigya login OK for UID={}", parsed.uid());
            return new GigyaSession(parsed.sessionInfo().sessionToken());
        }
    }

    private String gigyaGetJwt(GigyaSession session) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("apiKey", properties.getGigyaApiKey())
                .add("login_token", session.sessionToken())
                .add("format", "json")
                .add("expiration", "86400")
                .build();

        Request request = new Request.Builder()
                .url(GIGYA_JWT_URL)
                .post(body)
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            String rawBody = response.body() != null ? response.body().string() : "{}";
            GigyaJwtResponse parsed = objectMapper.readValue(rawBody, GigyaJwtResponse.class);

            if (parsed.errorCode() != 0) {
                log.error("Gigya getJWT error {} – {}", parsed.errorCode(), parsed.errorMessage());
                return null;
            }
            log.debug("Gigya JWT obtained successfully");
            return parsed.idToken();
        }
    }

    /**
     * POSTs the Gigya JWT to RaiPlay's social-login endpoint and collects the
     * resulting session cookies as a single {@code Cookie:} header value.
     */
    private String exchangeForRaiPlayCookie(String jwt) throws IOException {
        String exchangeUrl = properties.getGigyaExchangeUrl();
        String requestJson  = objectMapper.writeValueAsString(Map.of("idToken", jwt));

        Request request = new Request.Builder()
                .url(exchangeUrl)
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", properties.getBaseUrl())
                .addHeader("Referer", properties.getBaseUrl() + "/")
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            log.debug("RaiPlay JWT exchange returned HTTP {}", response.code());

            List<String> setCookies = response.headers("Set-Cookie");
            if (setCookies.isEmpty()) {
                log.warn("RaiPlay JWT exchange (HTTP {}) returned no Set-Cookie headers", response.code());
                return null;
            }

            // Build a "Cookie:" header value from all Set-Cookie entries.
            StringBuilder cookieHeader = new StringBuilder();
            for (String sc : setCookies) {
                String nameValue = sc.split(";")[0]; // "name=value" before any cookie attributes
                if (!cookieHeader.isEmpty()) cookieHeader.append("; ");
                cookieHeader.append(nameValue);
            }
            return cookieHeader.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Gigya response DTOs (internal to this service)
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GigyaLoginResponse(
            @JsonProperty("errorCode")    int errorCode,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("UID")          String uid,
            @JsonProperty("sessionInfo")  SessionInfo sessionInfo
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record SessionInfo(@JsonProperty("sessionToken") String sessionToken) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GigyaJwtResponse(
            @JsonProperty("errorCode")    int errorCode,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("id_token")     String idToken
    ) {}

    record GigyaSession(String sessionToken) {}
}
