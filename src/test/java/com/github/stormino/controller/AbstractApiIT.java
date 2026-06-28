package com.github.stormino.controller;

import com.github.stormino.service.DownloadQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiIT {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private DownloadQueueService downloadQueueService;

    @BeforeEach
    void prepareForTest() throws Exception {
        // 1. Clean the database.
        jdbc.execute("DELETE FROM download_sub_task");
        jdbc.execute("DELETE FROM download_task");
        jdbc.execute("DELETE FROM monitored_show");

        // 2. Clear the in-memory task map so DB cleanup is fully reflected by the API.
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
