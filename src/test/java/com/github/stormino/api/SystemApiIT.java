package com.github.stormino.api;

import com.github.stormino.api.dto.SettingsDto;
import com.github.stormino.api.dto.SystemDto;
import com.github.stormino.api.dto.ToolCheckDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("System API v1")
class SystemApiIT extends AbstractApiIT {

    @Test
    @DisplayName("GET /system reports version, runtime and disks")
    void system() {
        SystemDto sys = rest.getForObject("/api/v1/system", SystemDto.class);
        assertThat(sys.appVersion()).isNotBlank().doesNotContain("@");
        assertThat(sys.javaVersion()).startsWith("21");
        assertThat(sys.cpuCount()).isPositive();
        assertThat(sys.disks()).extracting(d -> d.label()).containsExactly("movies", "tvshows", "temp");
        assertThat(sys.disks()).allSatisfy(d -> assertThat(d.freeBytes()).isNotNull().isPositive());
    }

    @Test
    @DisplayName("GET /settings returns the read-only config")
    void settings() {
        SettingsDto s = rest.getForObject("/api/v1/settings", SettingsDto.class);
        assertThat(s.moviesPath()).endsWith("movies");
        assertThat(s.parallelDownloads()).isEqualTo(1);
        assertThat(s.defaultLanguage()).isEqualTo("en");
        assertThat(s.raiPlayEnabled()).isFalse();
        if (!s.tmdbApiKeySet()) {
            assertThat(s.tmdbApiKeyMasked()).isNull();
        } else {
            assertThat(s.tmdbApiKeyMasked()).startsWith("••••");
        }
    }

    @Test
    @DisplayName("POST /system/tools/check probes ffmpeg, 7zip and unrar")
    void toolsCheck() {
        ResponseEntity<List<ToolCheckDto>> resp = rest.exchange("/api/v1/system/tools/check", HttpMethod.POST, null,
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).extracting(ToolCheckDto::tool).containsExactly("ffmpeg", "7zip", "unrar");
        assertThat(resp.getBody()).allSatisfy(t -> {
            if (t.available()) {
                assertThat(t.version()).isNotBlank();
            } else {
                assertThat(t.error()).isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("OpenAPI spec uses a relative server URL")
    void openApi_relativeServer() {
        String spec = rest.getForObject("/v3/api-docs", String.class);
        assertThat(spec).contains("\"url\":\"/\"").doesNotContain("localhost:8080");
        assertThat(spec).contains("/api/v1/downloads", "/api/v1/events");
    }
}
