package com.github.stormino.service.link;

/** Download progress callback; may be called from worker threads, roughly once per second. */
@FunctionalInterface
public interface LinkProgress {

    LinkProgress NONE = (done, total, bps) -> { };

    /**
     * @param bytesDone      bytes on disk so far (including resumed data)
     * @param totalBytes     file size, or -1 when unknown
     * @param bytesPerSecond current speed
     */
    void update(long bytesDone, long totalBytes, double bytesPerSecond);

    /**
     * What the download is doing while no bytes flow (e.g. "Queued by bot: position 3");
     * {@code null} clears it.
     */
    default void status(String message) {
    }
}
