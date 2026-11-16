package com.github.stormino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableResilientMethods
@EnableScheduling
@SpringBootApplication
public class HarvesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarvesterApplication.class, args);
    }
}
