package com.github.stormino.api;

import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMapperTest {

    @Test
    void mapsTimestampsAsInstantsInServerZone() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        DownloadTask t = DownloadTask.builder().contentType(DownloadTask.ContentType.MOVIE).title("X")
                .createdAt(created).languages(List.of("en")).build();
        TaskDto dto = TaskMapper.toDto(t);
        assertThat(dto.createdAt()).isEqualTo(created.atZone(ZoneId.systemDefault()).toInstant());
        assertThat(dto.canCancel()).isTrue();
        assertThat(dto.canRetry()).isFalse();
        assertThat(dto.speedBps()).isNull();
    }

    @Test
    void computesNumericSpeedForDownloadingTask() {
        DownloadTask t = DownloadTask.builder().contentType(DownloadTask.ContentType.MOVIE).title("X")
                .status(DownloadStatus.DOWNLOADING).startedAt(LocalDateTime.now().minusSeconds(10))
                .languages(List.of("en")).build();
        DownloadSubTask video = DownloadSubTask.builder().type(DownloadSubTask.SubTaskType.VIDEO)
                .status(DownloadStatus.DOWNLOADING).downloadedBytes(10_000_000L).totalBytes(20_000_000L)
                .startedAt(LocalDateTime.now().minusSeconds(10)).build();
        t.getSubTasks().add(video);

        TaskDto dto = TaskMapper.toDto(t);
        assertThat(dto.speedBps()).isBetween(800_000.0, 1_100_000.0);
        assertThat(dto.progress()).isEqualTo(50.0);
        assertThat(dto.subTasks()).singleElement().satisfies(s -> {
            assertThat(s.speedBps()).isBetween(800_000.0, 1_100_000.0);
            assertThat(s.displayName()).isEqualTo("Video");
        });
    }

    @Test
    void finishedTasksAreRetryableExceptCompleted() {
        for (DownloadStatus s : List.of(DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
            TaskDto dto = TaskMapper.toDto(DownloadTask.builder().status(s).languages(List.of()).build());
            assertThat(dto.canRetry()).isTrue();
            assertThat(dto.canCancel()).isFalse();
        }
        TaskDto done = TaskMapper.toDto(DownloadTask.builder().status(DownloadStatus.COMPLETED).languages(List.of()).build());
        assertThat(done.canRetry()).isFalse();
        assertThat(done.progress()).isEqualTo(100.0);
    }
}
