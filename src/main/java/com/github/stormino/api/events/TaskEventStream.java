package com.github.stormino.api.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.github.stormino.api.TaskMapper;
import com.github.stormino.api.dto.TaskDto;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.DownloadQueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events hub for download tasks.
 *
 * <p>Producers (download threads) only mark tasks as changed; they never write to a
 * socket. A single dispatcher thread drains those marks, coalesces them per task
 * (at most one {@code task.updated} per task every {@value #THROTTLE_MS} ms; terminal
 * states skip the wait), and writes to every connected client. A slow client can
 * therefore delay other clients' events but never a download.
 *
 * <p>Events: {@code snapshot} (array of all tasks, on connect), {@code task.created},
 * {@code task.updated}, {@code task.removed} (each a full task DTO), plus a comment
 * heartbeat every {@value #HEARTBEAT_MS} ms.
 */
@Slf4j
@Component
public class TaskEventStream {

    static final long THROTTLE_MS = 250;
    static final long TICK_MS = 50;
    static final long HEARTBEAT_MS = 15_000;
    static final long EMITTER_TIMEOUT_MS = 30 * 60_000L;
    static final long CLIENT_RECONNECT_MS = 3_000;

    private final ObjectProvider<DownloadQueueService> queueService;
    private final ObjectMapper objectMapper;

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> ops = new ConcurrentLinkedQueue<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final Set<String> urgent = ConcurrentHashMap.newKeySet();
    /** Accessed only from the dispatcher thread. */
    private final Map<String, Long> lastSent = new HashMap<>();
    private long lastHeartbeat = System.currentTimeMillis();

    private ScheduledExecutorService dispatcher;

    public TaskEventStream(ObjectProvider<DownloadQueueService> queueService, ObjectMapper objectMapper) {
        this.queueService = queueService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void start() {
        dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-dispatcher");
            t.setDaemon(true);
            return t;
        });
        dispatcher.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        dispatcher.shutdownNow();
        clients.forEach(c -> {
            try {
                c.complete();
            } catch (Exception ignored) {
                // client already gone
            }
        });
        clients.clear();
    }

    // ---------------------------------------------------------------- producers

    /** Opens a stream for a new client; its first event is the snapshot. */
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> {
            clients.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> clients.remove(emitter));
        ops.add(() -> sendSnapshot(emitter));
        return emitter;
    }

    public void taskCreated(String taskId) {
        ops.add(() -> sendTask("task.created", taskId));
    }

    public void taskRemoved(DownloadTask task) {
        TaskDto dto = TaskMapper.toDto(task);
        ops.add(() -> {
            dirty.remove(dto.id());
            urgent.remove(dto.id());
            lastSent.remove(dto.id());
            broadcast("task.removed", toJson(dto));
        });
    }

    /** Marks a task as changed; terminal changes are sent on the next tick. */
    public void taskChanged(String taskId, boolean terminal) {
        if (taskId == null) {
            return;
        }
        (terminal ? urgent : dirty).add(taskId);
    }

    public int clientCount() {
        return clients.size();
    }

    // --------------------------------------------------------------- dispatcher

    void tick() {
        try {
            Runnable op;
            while ((op = ops.poll()) != null) {
                op.run();
            }
            long now = System.currentTimeMillis();
            for (String id : List.copyOf(urgent)) {
                urgent.remove(id);
                dirty.remove(id);
                sendTask("task.updated", id);
            }
            for (String id : List.copyOf(dirty)) {
                if (now - lastSent.getOrDefault(id, 0L) >= THROTTLE_MS) {
                    dirty.remove(id);
                    sendTask("task.updated", id);
                }
            }
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now;
                for (SseEmitter c : clients) {
                    send(c, SseEmitter.event().comment("heartbeat"));
                }
            }
        } catch (Throwable t) {
            log.warn("SSE dispatcher tick failed: {}", t.getMessage(), t);
        }
    }

    private void sendSnapshot(SseEmitter emitter) {
        List<TaskDto> all = new ArrayList<>(queueService.getObject().getAllTasks().stream()
                .map(TaskMapper::toDto)
                .toList());
        all.sort(Comparator.comparing(TaskDto::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
        boolean ok = send(emitter, SseEmitter.event()
                .name("snapshot")
                .reconnectTime(CLIENT_RECONNECT_MS)
                .data(toJson(all)));
        if (ok) {
            clients.add(emitter);
            log.debug("SSE client connected ({} total)", clients.size());
        }
    }

    private void sendTask(String event, String taskId) {
        DownloadTask task = queueService.getObject().getTask(taskId).orElse(null);
        lastSent.put(taskId, System.currentTimeMillis());
        if (task == null) {
            return;
        }
        broadcast(event, toJson(TaskMapper.toDto(task)));
    }

    private void broadcast(String event, String json) {
        for (SseEmitter c : clients) {
            send(c, SseEmitter.event().name(event).data(json));
        }
    }

    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (Exception e) {
            log.debug("Dropping SSE client: {}", e.getMessage());
            clients.remove(emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // already closed
            }
            return false;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise SSE payload", e);
        }
    }
}
