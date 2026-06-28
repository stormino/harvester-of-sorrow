package com.github.stormino.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;

import java.io.IOException;

@Slf4j
@Configuration
public class LogbookConfig {

    @Bean
    public Sink requestOnlySink() {
        return new Sink() {
            @Override
            public void write(Precorrelation precorrelation, HttpRequest request) throws IOException {
                String query = request.getQuery();
                log.info("{} {}{}",
                        request.getMethod(),
                        request.getPath(),
                        query.isEmpty() ? "" : "?" + query);
            }

            @Override
            public void write(Correlation correlation, HttpRequest request, HttpResponse response) {
                // responses not logged
            }
        };
    }
}
