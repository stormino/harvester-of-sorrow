package com.github.stormino;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableRetry
@EnableScheduling
@SpringBootApplication
@Theme("vixsrc")
public class VixSrcDownloaderApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(VixSrcDownloaderApplication.class, args);
    }
}
