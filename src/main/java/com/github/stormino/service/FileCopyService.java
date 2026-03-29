package com.github.stormino.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@Slf4j
public class FileCopyService {

    /**
     * Copies a file to a destination using rsync with checksum verification.
     * Writes to a hidden .tmp file first, then atomically renames.
     * Retries up to 3 times with exponential backoff (5s, 10s, 20s).
     */
    @Retryable(
            retryFor = IOException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5000, multiplier = 2)
    )
    public void copyWithVerification(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path tempDestination = destination.resolveSibling("." + destination.getFileName() + ".tmp");

        try {
            log.debug("Rsync-ing file to temporary destination: {}", tempDestination);

            ProcessBuilder rsyncProcess = new ProcessBuilder(
                    "rsync", "--checksum", "--whole-file", "--inplace",
                    source.toString(), tempDestination.toString()
            );
            rsyncProcess.redirectErrorStream(true);
            Process process = rsyncProcess.start();
            String rsyncOutput = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("rsync failed with exit code " + exitCode + ": " + rsyncOutput.trim());
            }

            Files.move(tempDestination, destination, StandardCopyOption.REPLACE_EXISTING);
            long fileSize = Files.size(destination);
            log.info("Successfully copied file to destination: {} ({} bytes)", destination, fileSize);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("rsync was interrupted", e);
        } finally {
            // Clean up temp file on any failure (no-op if move already succeeded)
            try { Files.deleteIfExists(tempDestination); } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp file {}: {}", tempDestination, cleanupEx.getMessage());
            }
        }
    }

    @Recover
    public void copyRecover(IOException e, Path source, Path destination) throws IOException {
        log.error("All copy attempts failed for {}: {}", destination, e.getMessage());
        throw e;
    }
}
