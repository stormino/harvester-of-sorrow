package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.SourceRefCodec;
import com.github.stormino.api.TaskMapper;
import com.github.stormino.api.dto.ClearResultDto;
import com.github.stormino.api.dto.DownloadRequest;
import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.config.AppProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.model.source.VixSrcMetadata;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.source.MediaSourceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/downloads")
@RequiredArgsConstructor
@Tag(name = "Downloads", description = "Download queue")
public class DownloadsController {

    private final DownloadQueueService queue;
    private final MediaSourceRegistry sourceRegistry;
    private final SourceRefCodec sourceRefCodec;
    private final AppProperties properties;

    @Operation(summary = "Queue a download",
            description = "Expands shows and seasons server-side and returns every task created.")
    @ApiResponse(responseCode = "201", description = "Tasks created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<TaskDto> create(@Valid @RequestBody DownloadRequest req) {
        var provider = sourceRegistry.all().stream()
                .filter(p -> p.source() == req.source())
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Source " + req.source() + " is not enabled"));

        List<String> languages = req.languages() == null ? List.of() : req.languages();
        Set<String> supported = provider.supportedLanguages();
        if (!supported.isEmpty()) {
            List<String> unsupported = languages.stream().filter(l -> !supported.contains(l)).toList();
            if (!unsupported.isEmpty()) {
                throw ApiException.badRequest("Languages not supported by " + req.source().getDisplayName()
                        + ": " + unsupported);
            }
        }
        // Subtitles come in many more languages than audio: any two-letter code is accepted.
        if (req.subtitleLanguages() != null) {
            List<String> invalid = req.subtitleLanguages().stream().filter(l -> !l.matches("[a-z]{2}")).toList();
            if (!invalid.isEmpty()) {
                throw ApiException.badRequest("Subtitle languages must be two-letter codes: " + invalid);
            }
        }
        if (req.quality() != null && !req.quality().isBlank() && !ContentController.isValidQuality(req.quality())) {
            throw ApiException.badRequest("Unknown quality '" + req.quality() + "'");
        }
        if (req.contentType() == DownloadTask.ContentType.MOVIE
                && ((req.seasons() != null && !req.seasons().isEmpty())
                || (req.episodes() != null && !req.episodes().isEmpty()))) {
            throw ApiException.badRequest("Movies take no seasons or episodes");
        }

        SourceMetadata meta = sourceRefCodec.decode(req.sourceRef(), req.source());
        ContentMetadata content = ContentMetadata.builder()
                .source(req.source())
                .sourceMetadata(meta)
                .contentType(req.contentType())
                .tmdbId(meta instanceof VixSrcMetadata v ? v.tmdbId() : null)
                .title(req.title())
                .year(req.year())
                .build();
        if (languages.isEmpty() && !supported.isEmpty()
                && !supported.contains(properties.getDownload().getDefaultLanguage())) {
            // Default language not served by this source (e.g. "en" on RaiPlay): use the source's own.
            languages = List.of(supported.iterator().next());
        }
        return queue.enqueue(content, req.contentType(), req.seasons(), req.episodes(), languages,
                        req.subtitleLanguages(), req.quality())
                .stream().map(TaskMapper::toDto).toList();
    }

    @Operation(summary = "List all tasks")
    @GetMapping
    public List<TaskDto> list() {
        return queue.getAllTasks().stream()
                .map(TaskMapper::toDto)
                .sorted(Comparator.comparing(TaskDto::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Operation(summary = "Get one task")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    public TaskDto get(@PathVariable String id) {
        return queue.getTask(id).map(TaskMapper::toDto).orElseThrow(() -> ApiException.notFound("Task", id));
    }

    @Operation(summary = "Cancel a task", description = "Allowed in any non-terminal state, including QUEUED.")
    @ApiResponse(responseCode = "404", description = "Not found")
    @ApiResponse(responseCode = "409", description = "Task is already finished")
    @PostMapping("/{id}/cancel")
    public TaskDto cancel(@PathVariable String id) {
        switch (queue.cancelTask(id)) {
            case NOT_FOUND -> throw ApiException.notFound("Task", id);
            case INVALID_STATE -> throw ApiException.conflict("Task " + id + " cannot be cancelled in its current state");
            default -> { }
        }
        return get(id);
    }

    @Operation(summary = "Retry a task", description = "Allowed for FAILED and CANCELLED tasks.")
    @ApiResponse(responseCode = "404", description = "Not found")
    @ApiResponse(responseCode = "409", description = "Task is not failed or cancelled")
    @PostMapping("/{id}/retry")
    public TaskDto retry(@PathVariable String id) {
        switch (queue.retryTask(id)) {
            case NOT_FOUND -> throw ApiException.notFound("Task", id);
            case INVALID_STATE -> throw ApiException.conflict("Task " + id + " can only be retried when failed or cancelled");
            default -> { }
        }
        return get(id);
    }

    @Operation(summary = "Clear finished tasks",
            description = "Removes completed, failed, cancelled and not-found tasks. Files on disk are kept.")
    @DeleteMapping
    public ClearResultDto clear(
            @Parameter(schema = @Schema(allowableValues = {"finished"}), required = true)
            @RequestParam String state) {
        if (!"finished".equalsIgnoreCase(state)) {
            throw ApiException.badRequest("Only state=finished is supported");
        }
        return new ClearResultDto(queue.clearCompletedTasks());
    }
}
