package com.github.stormino.service.link;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cancellation handle for one link download. */
public final class LinkControl {

    private volatile boolean cancelled;
    private final List<Runnable> onCancel = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        onCancel.forEach(Runnable::run);
    }

    /** Runs {@code action} on cancellation (immediately if already cancelled). */
    public void onCancel(Runnable action) {
        onCancel.add(action);
        if (cancelled) {
            action.run();
        }
    }
}
