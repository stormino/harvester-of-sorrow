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
    private int searchPageSize = 20;
    private boolean enabled = true;
}
