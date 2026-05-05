package com.github.stormino.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "raiplay")
public class RaiPlayProperties {

    private String baseUrl   = "https://www.raiplay.it";
    private String searchUrl = "https://www.raiplay.it/atomatic/raiplay-search-service/api/v1/msearch";

    /**
     * Template IDs used by the RaiPlay search frontend. They control which
     * input parameters are accepted and which output shape is returned. These
     * are baked into the public website JS and rotate occasionally; bump them
     * here if search starts returning unexpected shapes.
     */
    private String searchTemplateIn  = "6470a982e4e0301afe1f81f1";
    private String searchTemplateOut = "6516ac5d40da6c377b151642";

    private int searchPageSize = 48;

    private boolean enabled = true;

    // -------------------------------------------------------------------------
    // Manual session cookie (alternative to username/password)
    // -------------------------------------------------------------------------

    /**
     * Pre-authenticated browser cookie (full {@code Cookie:} header value)
     * captured after logging into raiplay.it. Used as a fallback when no
     * username/password are configured, or when auto-login fails.
     * Empty string disables injection.
     */
    private String sessionCookie = "";

    // -------------------------------------------------------------------------
    // Auto-login credentials (preferred over manual cookie)
    // -------------------------------------------------------------------------

    /** RaiPlay account e-mail. Set via {@code RAIPLAY_USERNAME} env var. */
    private String username = "";

    /** RaiPlay account password. Set via {@code RAIPLAY_PASSWORD} env var. */
    private String password = "";

    /**
     * Gigya (SAP CDC) site / API key used by RaiPlay.
     * This is a public constant embedded in the RaiPlay website JS.
     * To verify: open DevTools → Network, filter by {@code accounts.eu1.gigya.com},
     * and copy the {@code apiKey} query parameter from any request.
     * Override via {@code RAIPLAY_GIGYA_API_KEY} env var.
     */
    private String gigyaApiKey = "3_zEiEMnMEqZDFtEUvLMbJbMQq82GXvOBkE8MBG4gFqIjI7LyKVBJJXHjXoWgHJBm";

    /**
     * RaiPlay endpoint that accepts a Gigya JWT ({@code {"idToken":"..."}}) and
     * returns session cookies via {@code Set-Cookie} headers.
     *
     * <p>If the default doesn't work, open DevTools → Network during a browser
     * login, filter requests to {@code raiplay.it}, and look for the POST that
     * carries an {@code idToken} in its body.
     * Override via {@code RAIPLAY_GIGYA_EXCHANGE_URL} env var.
     */
    private String gigyaExchangeUrl = "https://www.raiplay.it/login/social";
}
