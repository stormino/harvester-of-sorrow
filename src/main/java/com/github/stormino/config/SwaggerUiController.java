package com.github.stormino.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * Serves the swagger-ui index.html via a Spring MVC @Controller (RequestMappingHandlerMapping,
 * order 0) so it takes precedence over Vaadin's IndexHtmlRequestHandler, which otherwise
 * intercepts every text/html navigation request — including the swagger-ui page — and renders
 * Vaadin's bootstrap instead.
 */
@Slf4j
@Controller
public class SwaggerUiController {

    private final ResourcePatternResolver resourcePatternResolver;
    private byte[] indexHtml;

    public SwaggerUiController(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @PostConstruct
    void loadIndexHtml() throws IOException {
        Resource[] resources = resourcePatternResolver.getResources(
                "classpath*:/META-INF/resources/webjars/swagger-ui/*/index.html");
        if (resources.length == 0) {
            log.warn("swagger-ui index.html not found in classpath — Swagger UI will not work");
            return;
        }
        indexHtml = resources[0].getInputStream().readAllBytes();
        log.info("swagger-ui index.html loaded from {}", resources[0].getDescription());
    }

    @GetMapping(value = "/swagger-ui/index.html", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> swaggerUiIndex() {
        if (indexHtml == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(indexHtml);
    }
}
