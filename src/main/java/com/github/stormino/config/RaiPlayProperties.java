package com.github.stormino.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "raiplay")
public class RaiPlayProperties {
    private String baseUrl = "https://www.raiplay.it";
    private String searchUrl = "https://www.raiplay.it/atomatic/raiplay-search-service/api/v1/msearch";

    /**
     * Template IDs used by the RaiPlay search frontend. They control which
     * input parameters are accepted and which output shape is returned. These
     * are baked into the public website JS and rotate occasionally; bump them
     * here if search starts returning unexpected shapes.
     */
    private String searchTemplateIn = "6470a982e4e0301afe1f81f1";
    private String searchTemplateOut = "6516ac5d40da6c377b151642";

    private int searchPageSize = 48;

    /**
     * Pre-authenticated browser cookie (full {@code Cookie:} header value)
     * captured after logging into raiplay.it. Required to fetch the master
     * playlist and segments for any content with {@code login_required=true}.
     * Empty string disables injection.
     */
    private String sessionCookie = "";

    private boolean enabled = true;
}
