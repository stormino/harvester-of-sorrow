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

    // -------------------------------------------------------------------------
    // Auto-login credentials – RaiPlay is fully disabled when either is blank.
    // -------------------------------------------------------------------------

    /** RaiPlay account e-mail. Set via {@code RAIPLAY_USERNAME} env var. */
    private String username = "";

    /** RaiPlay account password. Set via {@code RAIPLAY_PASSWORD} env var. */
    private String password = "";

    /**
     * Static key sent to {@code /atomatic/token-service/api/anonymize} to obtain a
     * per-session {@code domainApiKey}. Public constant embedded in the RaiPlay JS;
     * rotates rarely. Override via {@code RAIPLAY_ANONYMIZE_KEY} env var if needed.
     */
    private String anonymizeKey = "d0a5a58c2356f9b8f46262a206515222";
}
