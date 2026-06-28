package com.github.stormino.controller;

import com.github.stormino.service.ProgressBroadcastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
@Tag(name = "Progress")
public class ProgressController {

    private final ProgressBroadcastService progressBroadcastService;

    @Operation(
        summary = "Stream real-time progress updates (SSE)",
        description = """
            Server-Sent Events endpoint. Keep the connection open; the server pushes an event
            each time a download task changes state or makes progress.

            Each event `data` payload is a JSON object:
            ```json
            {
              "taskId": "uuid",
              "subTaskId": "uuid | null",
              "status": "DOWNLOADING",
              "progress": 42.5,
              "downloadSpeed": "5.2 MB/s",
              "etaSeconds": 138,
              "errorMessage": null,
              "timestamp": "2024-01-15T10:31:23"
            }
            ```

            **React usage:**
            ```ts
            const es = new EventSource('/api/progress/stream');
            es.onmessage = (e) => {
              const update = JSON.parse(e.data);
            };
            ```
            """
    )
    @ApiResponse(responseCode = "200", description = "SSE stream opened",
        content = @Content(mediaType = "text/event-stream",
            schema = @Schema(type = "string", description = "Newline-delimited SSE events")))
    @GetMapping("/stream")
    public SseEmitter streamProgress() {
        log.info("New SSE connection established");
        return progressBroadcastService.createEmitter();
    }
}
