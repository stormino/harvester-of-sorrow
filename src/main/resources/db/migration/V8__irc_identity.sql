-- The registered IRC nick HoS uses where an XDCC channel needs one (Settings > IRC account): one row.
CREATE TABLE irc_identity (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    nick          TEXT NOT NULL,
    password      TEXT NOT NULL,
    email         TEXT,
    auto_register INTEGER NOT NULL DEFAULT 0,
    updated_at    TEXT NOT NULL
);

-- Networks where a channel needed the registered nick, and how logging in there last went.
CREATE TABLE irc_network (
    host       TEXT PRIMARY KEY,   -- lower case
    port       INTEGER NOT NULL,
    nick       TEXT,
    state      TEXT NOT NULL,      -- IrcClient.AuthState
    message    TEXT,
    updated_at TEXT NOT NULL
);
