package com.github.stormino.service;

import com.github.stormino.api.events.TaskEventStream;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.ProgressUpdate;
import org.springframework.stereotype.Service;

/**
 * Entry point the download engine uses to announce task changes. It never blocks:
 * it only marks the task as changed in {@link TaskEventStream}, whose dispatcher
 * thread sends the current task state to clients.
 */
@Service
public class ProgressBroadcastService {

    private final TaskEventStream events;

    public ProgressBroadcastService(TaskEventStream events) {
        this.events = events;
    }

    public void broadcastProgress(ProgressUpdate update) {
        if (update == null) {
            return;
        }
        boolean terminal = update.getSubTaskId() == null && update.getStatus() != null
                && DownloadQueueService.isTerminal(update.getStatus());
        events.taskChanged(update.getTaskId(), terminal || update.getStatus() == DownloadStatus.QUEUED);
    }

    public void taskCreated(String taskId) {
        events.taskCreated(taskId);
    }

    public void taskRemoved(DownloadTask task) {
        events.taskRemoved(task);
    }
}
