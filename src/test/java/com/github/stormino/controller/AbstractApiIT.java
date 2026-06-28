package com.github.stormino.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.stormino.service.DownloadQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiIT {

    // Single WireMock instance for the entire test run.
    // Initialized in a static block so the port is stable when
    // @DynamicPropertySource reads it (before @BeforeAll would run).
    static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        Runtime.getRuntime().addShutdownHook(new Thread(wireMock::stop));
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private DownloadQueueService downloadQueueService;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("hos.extractor.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void prepareForTest() throws Exception {
        // 1. Return 404 for every VixSrc call so the download executor fails fast.
        wireMock.resetAll();
        wireMock.stubFor(WireMock.any(WireMock.anyUrl())
                .willReturn(WireMock.aResponse().withStatus(404)));

        // 2. Clean the database.
        jdbc.execute("DELETE FROM download_sub_task");
        jdbc.execute("DELETE FROM download_task");
        jdbc.execute("DELETE FROM monitored_show");

        // 3. Clear the in-memory task map so DB cleanup is fully reflected by the API.
        // Unwrap CGLIB proxy to reach the real target instance.
        Object target = downloadQueueService;
        if (org.springframework.aop.support.AopUtils.isAopProxy(target)) {
            target = ((org.springframework.aop.framework.Advised) target).getTargetSource().getTarget();
        }
        Field tasksField = target.getClass().getDeclaredField("tasks");
        tasksField.setAccessible(true);
        ((ConcurrentHashMap<?, ?>) tasksField.get(target)).clear();

        Field queueField = target.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        ((Queue<?>) queueField.get(target)).clear();
    }
}
