package com.github.stormino.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkOutputPathTest {

    @Test
    void keepsTheFilesOwnExtension() {
        assertEquals("/m/The.Matrix.1999.mkv", DownloadQueueService.withExtension("/m/The.Matrix.1999.mp4", "x.MKV"));
        assertEquals("/m/Show/Season 01/Show - S01E02.avi",
                DownloadQueueService.withExtension("/m/Show/Season 01/Show - S01E02.mp4", "a.b.avi"));
        // No usable extension: keep the default one.
        assertEquals("/m/A.2000.mp4", DownloadQueueService.withExtension("/m/A.2000.mp4", "noext"));
        assertEquals("/m/A.2000.mp4", DownloadQueueService.withExtension("/m/A.2000.mp4", "weird.ex t"));
    }
}
