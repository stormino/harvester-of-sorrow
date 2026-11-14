package com.github.stormino.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;
import org.zalando.logbook.core.CommonsLogFormatSink;

/**
 * One Apache-common-log line per request, written at INFO on the
 * {@code org.zalando.logbook} logger (Logbook's default writer uses TRACE).
 */
@Configuration
public class LogbookConfig {

    @Bean
    public Sink logbookSink() {
        return new CommonsLogFormatSink(new InfoHttpLogWriter());
    }

    static final class InfoHttpLogWriter implements HttpLogWriter {
        private static final Logger LOG = LoggerFactory.getLogger("org.zalando.logbook.Logbook");

        @Override
        public boolean isActive() {
            return LOG.isInfoEnabled();
        }

        @Override
        public void write(Precorrelation precorrelation, String request) {
            LOG.info(request);
        }

        @Override
        public void write(Correlation correlation, String response) {
            LOG.info(response);
        }
    }
}
