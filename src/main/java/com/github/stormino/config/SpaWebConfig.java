package com.github.stormino.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the React SPA from {@code classpath:/static/}. Hashed assets are cached for a
 * year; any other path that is not an API/doc/actuator route and not a file falls back to
 * {@code index.html} so client-side routes like {@code /downloads} survive a reload.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String[] BACKEND_PREFIXES = {"api/", "v3/", "swagger-ui", "actuator"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            for (String prefix : BACKEND_PREFIXES) {
                if (resourcePath.startsWith(prefix)) {
                    return null;
                }
            }
            // Unknown file with an extension (e.g. a stale asset): 404 rather than HTML.
            String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (last.contains(".")) {
                return null;
            }
            Resource index = new ClassPathResource("/static/index.html");
            return index.exists() ? index : null;
        }
    }
}
