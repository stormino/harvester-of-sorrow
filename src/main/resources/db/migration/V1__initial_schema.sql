CREATE TABLE IF NOT EXISTS download_task (
    id              TEXT PRIMARY KEY,
    content_type    TEXT NOT NULL,
    tmdb_id         INTEGER NOT NULL,
    season          INTEGER,
    episode         INTEGER,
    title           TEXT,
    episode_name    TEXT,
    year            INTEGER,
    languages       TEXT,       -- comma-separated list e.g. "en,it"
    quality         TEXT,
    output_path     TEXT,
    playlist_url    TEXT,
    status          TEXT NOT NULL DEFAULT 'QUEUED',
    error_message   TEXT,
    created_at      TEXT,
    started_at      TEXT,
    completed_at    TEXT
);

CREATE TABLE IF NOT EXISTS download_sub_task (
    id              TEXT PRIMARY KEY,
    parent_task_id  TEXT NOT NULL,
    type            TEXT NOT NULL,   -- VIDEO, AUDIO, SUBTITLE
    language        TEXT,
    title           TEXT,
    codec           TEXT,
    resolution      TEXT,
    bitrate         INTEGER,
    temp_file_path  TEXT,
    playlist_url    TEXT,
    status          TEXT NOT NULL DEFAULT 'QUEUED',
    error_message   TEXT,
    started_at      TEXT,
    completed_at    TEXT,
    FOREIGN KEY (parent_task_id) REFERENCES download_task(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sub_task_parent ON download_sub_task(parent_task_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON download_task(status);
