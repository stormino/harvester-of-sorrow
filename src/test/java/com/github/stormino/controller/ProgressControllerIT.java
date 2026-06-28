package com.github.stormino.controller;

import com.github.stormino.service.ProgressBroadcastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProgressController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("ProgressController integration tests")
class ProgressControllerIT {

    @Autowired
    MockMvc mvc;

    @MockBean
    ProgressBroadcastService progressBroadcastService;

    @Test
    @DisplayName("GET /api/progress/stream opens SSE emitter and returns 200")
    void streamProgressOpensEmitter() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(progressBroadcastService.createEmitter()).thenReturn(emitter);

        mvc.perform(get("/api/progress/stream"))
                .andExpect(status().isOk());

        verify(progressBroadcastService).createEmitter();
    }
}
