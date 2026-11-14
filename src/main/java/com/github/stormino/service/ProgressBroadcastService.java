package com.github.stormino.service;

import com.github.stormino.model.ProgressUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class ProgressBroadcastService {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Register a new SSE emitter
     */
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        String id = "global";

        emitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(id, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timed out");
            removeEmitter(id, emitter);
        });
        emitter.onError(e -> {
            log.warn("SSE emitter error: {}", e.getMessage());
            removeEmitter(id, emitter);
        });

        log.debug("New SSE emitter registered. Total: {}", emitters.get(id).size());

        return emitter;
    }

    /**
     * Broadcast progress update to all connected clients
     */
    public void broadcastProgress(ProgressUpdate update) {
        log.debug("Broadcasting progress for task {}: {}%", update.getTaskId(), update.getProgress());
        broadcastToSSE(update);
    }

    private void broadcastToSSE(ProgressUpdate update) {
        String id = "global";
        CopyOnWriteArrayList<SseEmitter> emitterList = emitters.get(id);

        if (emitterList == null || emitterList.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitterList) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(update));

            } catch (IOException e) {
                log.warn("Failed to send SSE event for task {}: {}", update.getTaskId(), e.getMessage());
                removeEmitter(id, emitter);
            }
        }
    }

    private void removeEmitter(String id, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitterList = emitters.get(id);
        if (emitterList != null) {
            emitterList.remove(emitter);
            log.debug("SSE emitter removed. Remaining: {}", emitterList.size());
        }
    }

    public int getActiveConnections() {
        return emitters.values().stream()
                .mapToInt(CopyOnWriteArrayList::size)
                .sum();
    }
}
