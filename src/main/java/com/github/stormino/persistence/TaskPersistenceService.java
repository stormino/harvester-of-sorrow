package com.github.stormino.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.SourceMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskPersistenceService {

    private final DownloadTaskRepository taskRepository;
    private final DownloadSubTaskRepository subTaskRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persist a newly created task.
     */
    public void saveTask(DownloadTask task) {
        DownloadTaskRecord r = toTaskRecord(task);
        taskRepository.insert(r.getId(), r.getSource(), r.getSourceMetadata(),
                r.getContentType(), r.getTmdbId(), r.getSeason(), r.getEpisode(),
                r.getTitle(), r.getEpisodeName(), r.getYear(), r.getLanguages(), r.getQuality(),
                r.getOutputPath(), r.getPlaylistUrl(), r.getStatus(), r.getErrorMessage(),
                r.getCreatedAt(), r.getStartedAt(), r.getCompletedAt());
        log.debug("Persisted task {}", task.getId());
    }

    /**
     * Persist status, timestamps, and errorMessage for an existing task.
     */
    public void updateTaskStatus(DownloadTask task) {
        taskRepository.updateStatus(
                task.getId(),
                task.getStatus().name(),
                task.getErrorMessage(),
                task.getStartedAt(),
                task.getCompletedAt()
        );
    }

    /**
     * Persist the resolved playlist URL for a task.
     */
    public void updateTaskPlaylistUrl(DownloadTask task) {
        taskRepository.updatePlaylistUrl(task.getId(), task.getPlaylistUrl());
    }

    /**
     * Persist all sub-tasks for a task (called once after initializeTrackSubTasks).
     */
    public void saveSubTasks(List<DownloadSubTask> subTasks) {
        for (DownloadSubTask subTask : subTasks) {
            DownloadSubTaskRecord r = toSubTaskRecord(subTask);
            subTaskRepository.insert(r.getId(), r.getParentTaskId(), r.getType(), r.getLanguage(),
                    r.getTitle(), r.getCodec(), r.getResolution(), r.getBitrate(),
                    r.getTempFilePath(), r.getPlaylistUrl(), r.getStatus(), r.getErrorMessage(),
                    r.getStartedAt(), r.getCompletedAt());
        }
        log.debug("Persisted {} sub-tasks", subTasks.size());
    }

    /**
     * Persist status, timestamps, and errorMessage for a sub-task.
     */
    public void updateSubTaskStatus(DownloadSubTask subTask) {
        subTaskRepository.updateStatus(
                subTask.getId(),
                subTask.getStatus().name(),
                subTask.getErrorMessage(),
                subTask.getStartedAt(),
                subTask.getCompletedAt()
        );
    }

    /**
     * Reset a task to QUEUED state for retry: clears status/timestamps/error and deletes old sub-tasks.
     */
    public void resetForRetry(DownloadTask task) {
        taskRepository.updateStatus(task.getId(), DownloadStatus.QUEUED.name(), null, null, null);
        subTaskRepository.deleteByParentTaskId(task.getId());
    }

    /**
     * Delete all tasks in terminal states (CASCADE removes their sub-tasks).
     */
    public void deleteCompleted() {
        taskRepository.deleteCompleted();
    }

    /**
     * Load all persisted tasks and their sub-tasks. Used during startup restore.
     */
    public List<DownloadTask> loadAll() {
        return StreamSupport.stream(taskRepository.findAll().spliterator(), false)
                .map(taskRecord -> {
                    List<DownloadSubTaskRecord> subTaskRecords =
                            subTaskRepository.findAllByParentTaskId(taskRecord.getId());
                    return fromTaskRecord(taskRecord, subTaskRecords);
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // Mapping: domain → record
    // -------------------------------------------------------------------------

    private DownloadTaskRecord toTaskRecord(DownloadTask task) {
        MediaSource source = task.getSource() != null ? task.getSource() : MediaSource.VIXSRC;
        return new DownloadTaskRecord(
                task.getId(),
                source.name(),
                serializeSourceMetadata(task.getSourceMetadata()),
                task.getContentType().name(),
                task.getTmdbId(),
                task.getSeason(),
                task.getEpisode(),
                task.getTitle(),
                task.getEpisodeName(),
                task.getYear(),
                task.getLanguages() != null ? String.join(",", task.getLanguages()) : null,
                task.getQuality(),
                task.getOutputPath(),
                task.getPlaylistUrl(),
                task.getStatus().name(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getCompletedAt()
        );
    }

    private String serializeSourceMetadata(SourceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize SourceMetadata", e);
        }
    }

    private SourceMetadata deserializeSourceMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SourceMetadata.class);
        } catch (JacksonException e) {
            log.error("Failed to deserialize SourceMetadata from {}: {}", json, e.getMessage());
            return null;
        }
    }

    private DownloadSubTaskRecord toSubTaskRecord(DownloadSubTask subTask) {
        return new DownloadSubTaskRecord(
                subTask.getId(),
                subTask.getParentTaskId(),
                subTask.getType().name(),
                subTask.getLanguage(),
                subTask.getTitle(),
                subTask.getCodec(),
                subTask.getResolution(),
                subTask.getBitrate(),
                subTask.getTempFilePath(),
                subTask.getPlaylistUrl(),
                subTask.getStatus().name(),
                subTask.getErrorMessage(),
                subTask.getStartedAt(),
                subTask.getCompletedAt()
        );
    }

    // -------------------------------------------------------------------------
    // Mapping: record → domain
    // -------------------------------------------------------------------------

    private DownloadTask fromTaskRecord(DownloadTaskRecord r, List<DownloadSubTaskRecord> subTaskRecords) {
        List<DownloadSubTask> subTasks = subTaskRecords.stream()
                .map(this::fromSubTaskRecord)
                .collect(Collectors.toCollection(CopyOnWriteArrayList::new));

        MediaSource source = r.getSource() != null
                ? MediaSource.valueOf(r.getSource())
                : MediaSource.VIXSRC;

        return DownloadTask.builder()
                .id(r.getId())
                .source(source)
                .sourceMetadata(deserializeSourceMetadata(r.getSourceMetadata()))
                .contentType(DownloadTask.ContentType.valueOf(r.getContentType()))
                .tmdbId(r.getTmdbId())
                .season(r.getSeason())
                .episode(r.getEpisode())
                .title(r.getTitle())
                .episodeName(r.getEpisodeName())
                .year(r.getYear())
                .languages(r.getLanguages() != null
                        ? Arrays.asList(r.getLanguages().split(","))
                        : List.of())
                .quality(r.getQuality())
                .outputPath(r.getOutputPath())
                .playlistUrl(r.getPlaylistUrl())
                .status(DownloadStatus.valueOf(r.getStatus()))
                .errorMessage(r.getErrorMessage())
                .createdAt(r.getCreatedAt())
                .startedAt(r.getStartedAt())
                .completedAt(r.getCompletedAt())
                .subTasks(subTasks)
                .build();
    }

    private DownloadSubTask fromSubTaskRecord(DownloadSubTaskRecord r) {
        return DownloadSubTask.builder()
                .id(r.getId())
                .parentTaskId(r.getParentTaskId())
                .type(DownloadSubTask.SubTaskType.valueOf(r.getType()))
                .language(r.getLanguage())
                .title(r.getTitle())
                .codec(r.getCodec())
                .resolution(r.getResolution())
                .bitrate(r.getBitrate())
                .tempFilePath(r.getTempFilePath())
                .playlistUrl(r.getPlaylistUrl())
                .status(DownloadStatus.valueOf(r.getStatus()))
                .errorMessage(r.getErrorMessage())
                .startedAt(r.getStartedAt())
                .completedAt(r.getCompletedAt())
                .build();
    }
}
