-- SQLite does not support ALTER COLUMN, so making tmdb_id nullable requires
-- recreating the table.  The column was defined NOT NULL in V1; RaiPlay tasks
-- have no TMDB ID.
--
-- Column order must match the live schema (V1 columns + V2 additions at end).

CREATE TABLE download_task_new (
    id              TEXT PRIMARY KEY,
    content_type    TEXT NOT NULL,
    tmdb_id         INTEGER,           -- was NOT NULL, now nullable for non-TMDB sources
    season          INTEGER,
    episode         INTEGER,
    title           TEXT,
    episode_name    TEXT,
    year            INTEGER,
    languages       TEXT,
    quality         TEXT,
    output_path     TEXT,
    playlist_url    TEXT,
    status          TEXT NOT NULL DEFAULT 'QUEUED',
    error_message   TEXT,
    created_at      TEXT,
    started_at      TEXT,
    completed_at    TEXT,
    source          TEXT NOT NULL DEFAULT 'VIXSRC',
    source_metadata TEXT CHECK (source_metadata IS NULL OR json_valid(source_metadata))
);

INSERT INTO download_task_new
    (id, content_type, tmdb_id, season, episode, title, episode_name, year,
     languages, quality, output_path, playlist_url, status, error_message,
     created_at, started_at, completed_at, source, source_metadata)
SELECT id, content_type, tmdb_id, season, episode, title, episode_name, year,
       languages, quality, output_path, playlist_url, status, error_message,
       created_at, started_at, completed_at, source, source_metadata
FROM download_task;

DROP TABLE download_task;

ALTER TABLE download_task_new RENAME TO download_task;

CREATE INDEX IF NOT EXISTS idx_task_status ON download_task(status);
CREATE INDEX IF NOT EXISTS idx_task_source ON download_task(source);
