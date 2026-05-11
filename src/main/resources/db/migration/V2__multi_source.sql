-- Add multi-source support: a source identifier and a JSON metadata blob.
-- The metadata is opaque from SQL's perspective and (de)serialized in Java
-- via Jackson polymorphism (see SourceMetadata).
--
-- Note: SQLite has no real JSON type. Declaring TEXT + a CHECK that uses
-- json_valid (from the JSON1 extension, built into the bundled SQLite) gives
-- us actual write-time validation. Declaring the column as JSON would only
-- be cosmetic.

ALTER TABLE download_task ADD COLUMN source TEXT NOT NULL DEFAULT 'VIXSRC';

ALTER TABLE download_task ADD COLUMN source_metadata TEXT
    CHECK (source_metadata IS NULL OR json_valid(source_metadata));

-- Backfill existing rows with VixSrc metadata reconstructed from tmdb_id /
-- season / episode. The "type" key matches @JsonTypeInfo on SourceMetadata.
UPDATE download_task
   SET source_metadata = json_object(
       'type',    'VIXSRC',
       'tmdbId',  tmdb_id,
       'season',  season,
       'episode', episode
   )
 WHERE source_metadata IS NULL;

CREATE INDEX IF NOT EXISTS idx_task_source ON download_task(source);
