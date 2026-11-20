-- Titles saved for later, per user (Authelia's Remote-User; "default" without it).
CREATE TABLE watchlist_item (
    id            TEXT PRIMARY KEY,
    user_name     TEXT NOT NULL,
    title_key     TEXT NOT NULL,   -- the search page's title key, e.g. "MOVIE:976893"
    content_type  TEXT NOT NULL,
    tmdb_id       INTEGER,
    title         TEXT NOT NULL,
    year          INTEGER,
    overview      TEXT,
    rating        REAL,
    poster_url    TEXT,
    external_url  TEXT,
    streams       TEXT NOT NULL CHECK (json_valid(streams)),  -- the search results (JSON), to reopen the title
    created_at    TEXT NOT NULL,
    UNIQUE (user_name, title_key)
);

CREATE INDEX IF NOT EXISTS idx_watchlist_user ON watchlist_item(user_name, created_at);
