package com.github.stormino.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadTaskCancelTest {

    @Test
    void cancelledTaskIgnoresLaterWorkerStatuses() {
        DownloadTask t = DownloadTask.builder().status(DownloadStatus.DOWNLOADING).build();
        t.setStatus(DownloadStatus.CANCELLED);
        t.setStatus(DownloadStatus.FAILED);
        t.setStatus(DownloadStatus.MERGING);
        t.setStatus(DownloadStatus.COMPLETED);
        assertThat(t.getStatus()).isEqualTo(DownloadStatus.CANCELLED);
    }

    @Test
    void retryLeavesCancelled() {
        DownloadTask t = DownloadTask.builder().status(DownloadStatus.CANCELLED).build();
        t.setStatus(DownloadStatus.QUEUED);
        assertThat(t.getStatus()).isEqualTo(DownloadStatus.QUEUED);
        t.setStatus(DownloadStatus.EXTRACTING);
        assertThat(t.getStatus()).isEqualTo(DownloadStatus.EXTRACTING);
    }
}
