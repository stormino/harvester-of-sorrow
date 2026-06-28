package com.github.stormino.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Sink;
import org.zalando.logbook.core.CommonsLogFormatSink;
import org.zalando.logbook.core.DefaultHttpLogWriter;

@Configuration
public class LogbookConfig {

    @Bean
    public Sink logbookSink() {
        return new CommonsLogFormatSink(new DefaultHttpLogWriter());
    }
}
