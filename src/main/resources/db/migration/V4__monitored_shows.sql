CREATE TABLE monitored_show (
    id                  TEXT PRIMARY KEY,
    title               TEXT NOT NULL,
    year                INTEGER,
    tmdb_id             INTEGER,
    source              TEXT NOT NULL DEFAULT 'VIXSRC',
    source_metadata     TEXT CHECK (source_metadata IS NULL OR json_valid(source_metadata)),
    directory_name      TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    last_checked_at     TEXT,
    last_new_episode_at TEXT,
    created_at          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_monitored_show_enabled ON monitored_show(enabled);
CREATE INDEX IF NOT EXISTS idx_monitored_show_source  ON monitored_show(source);
