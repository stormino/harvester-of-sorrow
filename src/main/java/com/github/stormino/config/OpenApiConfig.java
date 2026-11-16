package com.github.stormino.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${app.version:unknown}")
    private String appVersion;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Harvester of Sorrow API")
                        .description("""
                                REST API for Harvester of Sorrow — a video downloader supporting VixSrc and RaiPlay sources and direct links (Mega, HTTP).

                                **Key features:**
                                - Search movies and TV shows across multiple sources
                                - Download from a link: Mega files and folders, direct HTTP files
                                - Queue, monitor, cancel and retry downloads
                                - Real-time progress updates via Server-Sent Events
                                - TV show library scanning and automatic episode monitoring

                                **Notes:**
                                - No authentication is required
                                - VixSrc availability checks use the Italian (`it`) catalogue
                                - Live updates: `GET /api/v1/events` (`text/event-stream`)
                                - Errors are RFC 7807 `application/problem+json`
                                """)
                        .version(appVersion)
                        .contact(new Contact()
                                .name("stormino")
                                .url("https://github.com/stormino/harvester-of-sorrow"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url("/").description("This server")
                ));
    }
}
