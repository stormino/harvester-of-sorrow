package com.github.stormino.api.v1;

import com.github.stormino.api.events.TaskEventStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Live task updates")
public class EventsController {

    private final TaskEventStream events;

    @Operation(summary = "Task event stream (SSE)", description = """
            Named events, each `data` is JSON:
            - `snapshot`: array of all tasks, sent first on every (re)connect
            - `task.created`, `task.updated`, `task.removed`: one full task
            A `:heartbeat` comment is sent about every 15 s. Updates are throttled to ~4/s per task;
            terminal states are sent immediately. Clients should replace their state on `snapshot`
            and upsert/delete by id on the others.""")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        // Ask reverse proxies (nginx) not to buffer the stream.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return events.connect();
    }
}
