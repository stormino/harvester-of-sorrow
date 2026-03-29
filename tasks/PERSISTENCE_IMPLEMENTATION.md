# SQLite Persistence Implementation Guide

## Overview

Add SQLite-backed persistence to the download queue so that tasks and sub-tasks survive application restarts.

**Stack:** Spring Data JDBC + SQLite JDBC driver + Flyway migrations

**Scope:**
- Persist all `DownloadTask` and `DownloadSubTask` instances to SQLite
- On restart: QUEUED tasks are re-queued, in-flight tasks (DOWNLOADING/EXTRACTING/MERGING/COPYING) are marked FAILED, COMPLETED/FAILED/CANCELLED tasks are restored as history
- Schema versioned via Flyway migrations
- Domain objects (`DownloadTask`, `DownloadSubTask`) are **not modified** — separate persistence records are used

---

## 1. Dependencies — `pom.xml`

Add inside `<dependencies>`:

```xml
<!-- Spring Data JDBC -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jdbc</artifactId>
</dependency>

<!-- SQLite JDBC driver -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.0.0</version>
</dependency>

<!-- Flyway database migrations -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

---

## 2. Configuration — `src/main/resources/application.yml`

Add the following block under the existing `spring:` key (alongside `spring.application.name`, `spring.vaadin`, etc.):

```yaml
  datasource:
    url: jdbc:sqlite:${SQLITE_DB_PATH:data/vixsrc.db}
    driver-class-name: org.sqlite.JDBC

  flyway:
    enabled: true
    locations: classpath:db/migration

  sql:
    init:
      mode: never
```

Add the `SQLITE_DB_PATH` variable to the environment variables table in `CLAUDE.md`:

| `SQLITE_DB_PATH` | Path to the SQLite database file | `data/vixsrc.db` |

---

## 3. Flyway Migration — `src/main/resources/db/migration/V1__initial_schema.sql`

Create this new file:

```sql
CREATE TABLE IF NOT EXISTS download_task (
    id              TEXT PRIMARY KEY,
    content_type    TEXT NOT NULL,
    tmdb_id         INTEGER NOT NULL,
    season          INTEGER,
    episode         INTEGER,
    title           TEXT,
    episode_name    TEXT,
    year            INTEGER,
    languages       TEXT,       -- comma-separated list e.g. "en,it"
    quality         TEXT,
    output_path     TEXT,
    playlist_url    TEXT,
    status          TEXT NOT NULL DEFAULT 'QUEUED',
    error_message   TEXT,
    created_at      TEXT,
    started_at      TEXT,
    completed_at    TEXT
);

CREATE TABLE IF NOT EXISTS download_sub_task (
    id              TEXT PRIMARY KEY,
    parent_task_id  TEXT NOT NULL,
    type            TEXT NOT NULL,   -- VIDEO, AUDIO, SUBTITLE
    language        TEXT,
    title           TEXT,
    codec           TEXT,
    resolution      TEXT,
    bitrate         INTEGER,
    temp_file_path  TEXT,
    playlist_url    TEXT,
    status          TEXT NOT NULL DEFAULT 'QUEUED',
    error_message   TEXT,
    started_at      TEXT,
    completed_at    TEXT,
    FOREIGN KEY (parent_task_id) REFERENCES download_task(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sub_task_parent ON download_sub_task(parent_task_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON download_task(status);
```

---

## 4. New File — `src/main/java/com/github/stormino/persistence/DownloadTaskRecord.java`

Spring Data JDBC aggregate root. Maps to `download_task` table. No `@MappedCollection` — sub-tasks are managed independently to allow concurrent partial updates without destroying and recreating the collection.

```java
package com.github.stormino.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("download_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTaskRecord {

    @Id
    private String id;
    private String contentType;
    private Integer tmdbId;
    private Integer season;
    private Integer episode;
    private String title;
    private String episodeName;
    private Integer year;
    private String languages;    // comma-separated: "en,it"
    private String quality;
    private String outputPath;
    private String playlistUrl;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
```

---

## 5. New File — `src/main/java/com/github/stormino/persistence/DownloadSubTaskRecord.java`

```java
package com.github.stormino.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("download_sub_task")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadSubTaskRecord {

    @Id
    private String id;
    private String parentTaskId;
    private String type;         // VIDEO, AUDIO, SUBTITLE
    private String language;
    private String title;
    private String codec;
    private String resolution;
    private Long bitrate;
    private String tempFilePath;
    private String playlistUrl;
    private String status;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
```

---

## 6. New File — `src/main/java/com/github/stormino/persistence/DownloadTaskRepository.java`

```java
package com.github.stormino.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface DownloadTaskRepository extends CrudRepository<DownloadTaskRecord, String> {

    @Modifying
    @Query("UPDATE download_task SET status = :status, error_message = :errorMessage, " +
           "started_at = :startedAt, completed_at = :completedAt WHERE id = :id")
    void updateStatus(@Param("id") String id,
                      @Param("status") String status,
                      @Param("errorMessage") String errorMessage,
                      @Param("startedAt") LocalDateTime startedAt,
                      @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Query("UPDATE download_task SET playlist_url = :playlistUrl WHERE id = :id")
    void updatePlaylistUrl(@Param("id") String id, @Param("playlistUrl") String playlistUrl);

    @Modifying
    @Query("DELETE FROM download_task WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'NOT_FOUND')")
    void deleteCompleted();
}
```

---

## 7. New File — `src/main/java/com/github/stormino/persistence/DownloadSubTaskRepository.java`

```java
package com.github.stormino.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DownloadSubTaskRepository extends CrudRepository<DownloadSubTaskRecord, String> {

    List<DownloadSubTaskRecord> findAllByParentTaskId(String parentTaskId);

    @Modifying
    @Query("UPDATE download_sub_task SET status = :status, error_message = :errorMessage, " +
           "started_at = :startedAt, completed_at = :completedAt WHERE id = :id")
    void updateStatus(@Param("id") String id,
                      @Param("status") String status,
                      @Param("errorMessage") String errorMessage,
                      @Param("startedAt") LocalDateTime startedAt,
                      @Param("completedAt") LocalDateTime completedAt);
}
```

---

## 8. New File — `src/main/java/com/github/stormino/persistence/TaskPersistenceService.java`

This is the central persistence service. It maps between domain objects and DB records, and exposes simple methods for the rest of the application to call. It has **no dependency on `DownloadQueueService`** — that dependency flows the other way.

```java
package com.github.stormino.persistence;

import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskPersistenceService {

    private final DownloadTaskRepository taskRepository;
    private final DownloadSubTaskRepository subTaskRepository;

    /**
     * Persist a newly created task.
     */
    public void saveTask(DownloadTask task) {
        taskRepository.save(toTaskRecord(task));
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
        subTaskRepository.saveAll(subTasks.stream().map(this::toSubTaskRecord).toList());
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
     * Delete all tasks in terminal states and their sub-tasks (CASCADE handles sub-tasks).
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
        return new DownloadTaskRecord(
                task.getId(),
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
                .collect(java.util.stream.Collectors.toCollection(CopyOnWriteArrayList::new));

        return DownloadTask.builder()
                .id(r.getId())
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
                .status(com.github.stormino.model.DownloadStatus.valueOf(r.getStatus()))
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
                .status(com.github.stormino.model.DownloadStatus.valueOf(r.getStatus()))
                .errorMessage(r.getErrorMessage())
                .startedAt(r.getStartedAt())
                .completedAt(r.getCompletedAt())
                .build();
    }
}
```

---

## 9. New File — `src/main/java/com/github/stormino/config/DatabaseConfig.java`

Explicitly registers `AnsiDialect` for SQLite, since Spring Data JDBC does not ship a dedicated `SQLiteDialect`. `AnsiDialect` covers all SQL operations we use (simple CRUD, `@Modifying @Query`).

```java
package com.github.stormino.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.dialect.AnsiDialect;
import org.springframework.data.relational.core.dialect.Dialect;

@Configuration
public class DatabaseConfig {

    @Bean
    public Dialect jdbcDialect() {
        return AnsiDialect.INSTANCE;
    }
}
```

---

## 10. Modified File — `src/main/java/com/github/stormino/service/DownloadQueueService.java`

### 10a. Add field injection

Add `TaskPersistenceService` as a constructor-injected dependency:

```java
// Add import
import com.github.stormino.persistence.TaskPersistenceService;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
```

Add to constructor parameters and field:

```java
private final TaskPersistenceService persistenceService;

// Add to constructor signature:
public DownloadQueueService(..., TaskPersistenceService persistenceService) {
    ...
    this.persistenceService = persistenceService;
}
```

### 10b. Add startup restore

Add this method to `DownloadQueueService`:

```java
/**
 * On startup, restore tasks from the database.
 * In-flight tasks (interrupted by the restart) are marked as FAILED.
 * QUEUED tasks are re-added to the queue.
 */
@EventListener(ApplicationReadyEvent.class)
public void restoreFromDatabase() {
    log.info("Restoring download queue from database...");

    List<DownloadStatus> inFlight = List.of(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.EXTRACTING,
            DownloadStatus.MERGING,
            DownloadStatus.COPYING
    );

    List<DownloadTask> storedTasks = persistenceService.loadAll();

    for (DownloadTask task : storedTasks) {
        if (inFlight.contains(task.getStatus())) {
            task.setStatus(DownloadStatus.FAILED);
            task.setErrorMessage("Application was restarted during download");
            persistenceService.updateTaskStatus(task);
        }
        tasks.put(task.getId(), task);
        if (task.getStatus() == DownloadStatus.QUEUED) {
            queue.offer(task);
        }
    }

    log.info("Restored {} tasks ({} re-queued)", storedTasks.size(),
            storedTasks.stream().filter(t -> t.getStatus() == DownloadStatus.QUEUED).count());

    if (!queue.isEmpty()) {
        processQueue();
    }
}
```

### 10c. Persist task on creation

In `addSingleDownload()`, after `tasks.put(task.getId(), task);`:

```java
tasks.put(task.getId(), task);
queue.offer(task);
persistenceService.saveTask(task);   // ADD THIS LINE
```

In `createTaskWithoutMetadataFetch()`, same location — after `tasks.put(task.getId(), task);`:

```java
tasks.put(task.getId(), task);
queue.offer(task);
persistenceService.saveTask(task);   // ADD THIS LINE
```

### 10d. Persist status changes

In `updateTaskStatus()`, after setting the task fields and before broadcasting:

```java
private void updateTaskStatus(DownloadTask task, DownloadStatus status, Double progress, String message) {
    task.setStatus(status);
    task.setProgress(progress);
    persistenceService.updateTaskStatus(task);    // ADD THIS LINE

    ProgressUpdate update = ProgressUpdate.builder()
            ...
```

### 10e. Persist playlist URL

In `processTaskAsync()`, after `task.setPlaylistUrl(playlistInfo.get().getUrl());`:

```java
task.setPlaylistUrl(playlistInfo.get().getUrl());
persistenceService.updateTaskPlaylistUrl(task);   // ADD THIS LINE
```

### 10f. Persist cancellation

In `cancelTask()`, after `task.setStatus(DownloadStatus.CANCELLED);`:

```java
task.setStatus(DownloadStatus.CANCELLED);
queue.remove(task);
persistenceService.updateTaskStatus(task);        // ADD THIS LINE
```

### 10g. Persist clear

In `clearCompletedTasks()`, after computing the list to remove and before removing from the map:

```java
toRemove.forEach(tasks::remove);
persistenceService.deleteCompleted();             // ADD THIS LINE
```

---

## 11. Modified File — `src/main/java/com/github/stormino/service/TrackDownloadOrchestrator.java`

### 11a. Add field injection

```java
// Add import
import com.github.stormino.persistence.TaskPersistenceService;
```

Add to `@RequiredArgsConstructor` fields (already uses `@RequiredArgsConstructor`):

```java
private final TaskPersistenceService persistenceService;
```

### 11b. Persist sub-tasks after initialization

In `downloadWithTracks()`, after `task.setSubTasks(subTasks);`:

```java
task.setSubTasks(subTasks);
persistenceService.saveSubTasks(subTasks);        // ADD THIS LINE

// Broadcast initial tree structure
broadcastTaskStructure(task);
```

### 11c. Persist sub-task status on completion/failure

In `downloadTrackAsync()`, in the success branch after `subTask.setCompletedAt(...)`:

```java
subTask.setStatus(DownloadStatus.COMPLETED);
subTask.setProgress(100.0);
subTask.setCompletedAt(LocalDateTime.now());
subTask.setDownloadSpeed(null);
subTask.setEtaSeconds(null);
persistenceService.updateSubTaskStatus(subTask);  // ADD THIS LINE
broadcastSubTaskUpdate(task, subTask);
```

In `downloadTrackAsync()`, in the failure branch after the status is set:

```java
} else {
    if (result.getStatus() == DownloadResult.ResultStatus.NOT_FOUND) {
        // Status already set by strategy
    } else if (subTask.getStatus() != DownloadStatus.NOT_FOUND) {
        subTask.setStatus(DownloadStatus.FAILED);
        subTask.setErrorMessage(result.getErrorMessage() != null ? result.getErrorMessage() : "Download failed");
    }
    persistenceService.updateSubTaskStatus(subTask);  // ADD THIS LINE
    broadcastSubTaskUpdate(task, subTask);
```

### 11d. Persist task status changes in mergeTracks

In `mergeTracks()`, the method already calls `task.setStatus(...)` in several places, and `downloadWithTracks()` calls `TrackDownloadOrchestrator` which triggers status updates in `DownloadQueueService.updateTaskStatus()`. However, the `mergeTracks()` method sets status directly on the task without going through `DownloadQueueService.updateTaskStatus()`.

Add `persistenceService.updateTaskStatus(task)` after each direct `task.setStatus(...)` call in `mergeTracks()`:

```java
// Example — every occurrence of task.setStatus(...) in mergeTracks() should be followed by:
task.setStatus(DownloadStatus.MERGING);
task.setProgress(0.0);
persistenceService.updateTaskStatus(task);        // ADD THIS LINE
broadcastParentUpdate(task);
```

Do the same for all other direct `task.setStatus()` calls within `mergeTracks()` (COPYING, FAILED, COMPLETED).

Also in `downloadWithTracks()` where task status is set directly:

```java
// After: task.setStatus(DownloadStatus.FAILED) inside downloadWithTracks()
task.setStatus(DownloadStatus.FAILED);
persistenceService.updateTaskStatus(task);        // ADD THIS LINE
broadcastParentUpdate(task);
```

And after the completion block:

```java
task.setStatus(DownloadStatus.COMPLETED);
task.setProgress(100.0);
task.setCompletedAt(LocalDateTime.now());
...
persistenceService.updateTaskStatus(task);        // ADD THIS LINE
broadcastParentUpdate(task);
```

---

## 12. Dockerfile update

If using Docker, add the data directory and configure the SQLite path:

In `docker-compose.yml`, add a volume for the database file:

```yaml
volumes:
  - ./data:/app/data
environment:
  SQLITE_DB_PATH: /app/data/vixsrc.db
```

---

## Summary of New Files

| File | Purpose |
|------|---------|
| `src/main/resources/db/migration/V1__initial_schema.sql` | Flyway DDL migration |
| `src/main/java/com/github/stormino/persistence/DownloadTaskRecord.java` | DB entity for tasks |
| `src/main/java/com/github/stormino/persistence/DownloadSubTaskRecord.java` | DB entity for sub-tasks |
| `src/main/java/com/github/stormino/persistence/DownloadTaskRepository.java` | Spring Data JDBC repository |
| `src/main/java/com/github/stormino/persistence/DownloadSubTaskRepository.java` | Spring Data JDBC repository |
| `src/main/java/com/github/stormino/persistence/TaskPersistenceService.java` | Maps domain ↔ records, persistence facade |
| `src/main/java/com/github/stormino/config/DatabaseConfig.java` | Registers AnsiDialect for SQLite |

## Summary of Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Add 3 dependencies |
| `src/main/resources/application.yml` | Add datasource + flyway config |
| `DownloadQueueService.java` | Inject persistence service; save on create; update on status change/cancel/clear; startup restore |
| `TrackDownloadOrchestrator.java` | Inject persistence service; save sub-tasks on init; update sub-task status; update task status in mergeTracks |

---

## Implementation Notes

### SQLite WAL mode (optional performance improvement)

SQLite's default journal mode can be slow for concurrent reads. Add WAL mode to the migration:

```sql
PRAGMA journal_mode=WAL;
```

Add this as the first line of `V1__initial_schema.sql`.

### LocalDateTime storage

SQLite has no native timestamp type. The `sqlite-jdbc` driver stores `LocalDateTime` values as ISO-8601 text strings (e.g. `2024-01-15T10:30:00`). Spring Data JDBC handles the conversion transparently via JDBC `Timestamp`.

### Flyway + SQLite

Flyway 9.x (used by Spring Boot 3.2) supports SQLite in the community edition. The `flyway_schema_history` table is created automatically on first run. No additional Flyway configuration is needed beyond what is shown in section 2.

### Thread safety

The SQLite JDBC driver in WAL mode supports concurrent reads but serializes writes. Since `TaskPersistenceService` methods are called from multiple threads (concurrent track downloads), this is fine — SQLite will serialize DB writes while the domain objects remain in-memory and unblocked.

### Build verification

After implementation, verify with:
```bash
mvn clean package -DskipTests
java -jar target/vixsrc-downloader-*.jar
# Check logs for: "Flyway Community Edition ... has successfully applied 1 migration"
# Check logs for: "Restoring download queue from database..."
```
