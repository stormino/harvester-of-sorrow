-- Titles saved before they could be downloaded ("waiting") and the daily check that finds them.
ALTER TABLE watchlist_item ADD COLUMN release_date TEXT;                -- TMDB release / first air date
ALTER TABLE watchlist_item ADD COLUMN names TEXT;                       -- JSON array: other names to look it up by
ALTER TABLE watchlist_item ADD COLUMN waiting INTEGER NOT NULL DEFAULT 0;  -- 1 until a source has it
ALTER TABLE watchlist_item ADD COLUMN checked_at TEXT;                  -- last daily check
ALTER TABLE watchlist_item ADD COLUMN available_since TEXT;             -- when the check found it
ALTER TABLE watchlist_item ADD COLUMN available_sources TEXT;           -- e.g. "VIXSRC,XDCC"
ALTER TABLE watchlist_item ADD COLUMN available_query TEXT;             -- the name that found it (search page query)
ALTER TABLE watchlist_item ADD COLUMN seen_at TEXT;                     -- the user opened it since it became available
