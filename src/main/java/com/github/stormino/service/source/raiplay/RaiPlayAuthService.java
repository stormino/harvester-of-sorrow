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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Ordered patterns used to discover the Gigya site key from RaiPlay's HTML.
     * Tried in order; the first match wins. More specific patterns first avoids
     * picking up unrelated {@code 3_…} strings from other embedded SDKs.
     *
     * <p>Pattern 1 – Gigya CDN script URL (most reliable):
     *   {@code <script src="https://cdns.eu1.gigya.com/JS/gigya.js?apikey=3_...">}
     * <p>Pattern 2 – JSON config object (common for SPA bundles):
     *   {@code "apiKey":"3_..."} or {@code "apiKey": "3_..."}
     * <p>Pattern 3 – generic fallback (last resort, may match unrelated keys):
     *   any bare {@code 3_[A-Za-z0-9_-]{50,80}} string
     */
    private static final Pattern[] GIGYA_KEY_PATTERNS = {
            Pattern.compile(
                    "cdns?\\.eu1\\.gigya\\.com/[^\"']*[?&]apikey=(3_[A-Za-z0-9_-]{40,})",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "\"apiKey\"\\s*:\\s*\"(3_[A-Za-z0-9_-]{40,})\"",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "(3_[A-Za-z0-9_-]{50,80})")
    };

    /** Gigya error code returned when the apiKey parameter is wrong/rotated. */
    private static final int GIGYA_ERR_INVALID_API_KEY = 400093;

    private final ObjectMapper objectMapper;
    private final RaiPlayProperties properties;

    // Internal client: no circular dependency with the application OkHttpClient bean.
    private OkHttpClient authClient;

    private volatile String activeCookie = null;
    private volatile Instant cookieExpiry = Instant.EPOCH;

    /**
     * The Gigya site key we'll send with the next request. Starts as the value
     * from configuration; replaced at runtime if the configured key is rejected
     * and we successfully scrape a fresh one from the RaiPlay homepage. Lets
     * the app survive RaiPlay rotating their public site key without an
     * application restart or env-var update.
     */
    private volatile String activeApiKey;

    @PostConstruct
    public void init() {
        authClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .followRedirects(true)
                .build();

        activeApiKey = properties.getGigyaApiKey();
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
            GigyaLoginResult login = gigyaLogin(activeApiKey);

            // Detect a rotated API key and recover by scraping the current one
            // from the RaiPlay homepage. Without this, the app needs a restart
            // (and an env-var update) every time RaiPlay rotates their site key.
            if (login.invalidApiKey()) {
                String discovered = discoverGigyaApiKey();
                if (discovered != null && !discovered.equals(activeApiKey)) {
                    log.info("Configured Gigya API key was rejected; retrying with key discovered "
                            + "from RaiPlay homepage");
                    activeApiKey = discovered;
                    login = gigyaLogin(activeApiKey);
                }
            }

            if (login.session() == null) return;

            String jwt = gigyaGetJwt(login.session());
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

    private GigyaLoginResult gigyaLogin(String apiKey) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("loginID", properties.getUsername())
                .add("password", properties.getPassword())
                .add("apiKey", apiKey)
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
                return new GigyaLoginResult(null, parsed.errorCode() == GIGYA_ERR_INVALID_API_KEY);
            }
            if (parsed.sessionInfo() == null) {
                log.error("Gigya login returned no sessionInfo");
                return new GigyaLoginResult(null, false);
            }
            log.debug("Gigya login OK for UID={}", parsed.uid());
            return new GigyaLoginResult(new GigyaSession(parsed.sessionInfo().sessionToken()), false);
        }
    }

    /**
     * Scrape the current Gigya site key from the RaiPlay login page (or
     * homepage as fallback). Tries several patterns from most specific to
     * least specific so we don't accidentally pick up an unrelated {@code 3_…}
     * string from another embedded third-party SDK.
     *
     * @return the discovered key, or {@code null} if nothing was found
     */
    private String discoverGigyaApiKey() {
        String[] urlsToSearch = {
                properties.getBaseUrl() + "/login",
                properties.getBaseUrl() + "/"
        };

        for (String url : urlsToSearch) {
            String html = fetchPage(url);
            if (html == null) continue;

            for (int i = 0; i < GIGYA_KEY_PATTERNS.length; i++) {
                Matcher m = GIGYA_KEY_PATTERNS[i].matcher(html);
                if (m.find()) {
                    // Patterns 0 and 1 use capturing group 1; pattern 2 uses group 1 too.
                    String key = m.group(1);
                    log.info("Discovered Gigya API key via pattern#{} from {} "
                                    + "(prefix={}, length={})",
                            i, url, key.substring(0, Math.min(12, key.length())), key.length());
                    return key;
                }
            }
            log.warn("Gigya key discovery: no key found in {} ({} bytes)", url, html.length());
        }
        return null;
    }

    private String fetchPage(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "it-IT,it;q=0.9")
                .build();
        try (Response response = authClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Gigya key discovery: HTTP {} from {}", response.code(), url);
                return null;
            }
            return response.body().string();
        } catch (IOException e) {
            log.warn("Gigya key discovery: fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String gigyaGetJwt(GigyaSession session) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("apiKey", activeApiKey)
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

    /** Outcome of a {@link #gigyaLogin(String)} call. */
    private record GigyaLoginResult(GigyaSession session, boolean invalidApiKey) {}
}
