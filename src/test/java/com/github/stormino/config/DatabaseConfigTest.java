package com.github.stormino.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigTest {

    @Test
    void legacyDatabase_usedOnlyWhenConfiguredMissingAndLegacyPresent() throws Exception {
        Path legacy = DatabaseConfig.LEGACY_DB;
        boolean created = false;
        if (!Files.exists(legacy)) {
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy, "");
            created = true;
        }
        try {
            assertThat(DatabaseConfig.legacyDatabase(Paths.get("data", "does-not-exist-hos.db"))).isEqualTo(legacy);
            assertThat(DatabaseConfig.legacyDatabase(legacy)).isNull();
            Path existing = Files.createTempFile("hos", ".db");
            assertThat(DatabaseConfig.legacyDatabase(existing)).isNull();
            Files.delete(existing);
        } finally {
            if (created) {
                Files.delete(legacy);
            }
        }
    }
}
