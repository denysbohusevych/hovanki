-- Accounts, friends, blocks, groups and chat reports (docs/adr/0004-accounts-friends-chat.md).
-- Migrations are only ever appended: never edit one that has been applied, add V2__... instead.
-- Times are timestamptz written from the server's Clock (never SQL now()), so tests can move time.
-- Every row of a user goes with the user (ON DELETE CASCADE); reports stay, they have no foreign keys.

CREATE TABLE users (
    id                text        PRIMARY KEY,
    nickname          text        NOT NULL,
    -- NFKC + lowercase of the nickname, lowercase of the email: uniqueness ignores case.
    nickname_key      text        NOT NULL,
    email             text        NOT NULL,
    email_key         text        NOT NULL,
    -- Null until the emailed code is entered.
    email_verified_at timestamptz,
    password_hash     text        NOT NULL,
    -- Language of the emails: en, ru or uk.
    language          text        NOT NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT users_nickname_key_unique UNIQUE (nickname_key),
    CONSTRAINT users_email_key_unique UNIQUE (email_key)
);

-- DataRetention: unverified accounts are deleted after a week.
CREATE INDEX users_unverified_created_at ON users (created_at) WHERE email_verified_at IS NULL;

-- One live code per user and purpose: a new code replaces the old one.
CREATE TABLE email_codes (
    user_id    text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    code_hash  text        NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts   integer     NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, purpose)
);

CREATE INDEX email_codes_expires_at ON email_codes (expires_at);

-- Logged-in devices; only the SHA-256 of the token is stored.
CREATE TABLE account_sessions (
    token_hash   text        PRIMARY KEY,
    user_id      text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz NOT NULL
);

CREATE INDEX account_sessions_user_id ON account_sessions (user_id);
CREATE INDEX account_sessions_last_used_at ON account_sessions (last_used_at);

CREATE TABLE friend_requests (
    from_user  text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_user    text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (from_user, to_user),
    CHECK (from_user <> to_user)
);

CREATE INDEX friend_requests_to_user ON friend_requests (to_user);
CREATE INDEX friend_requests_created_at ON friend_requests (created_at);

-- Two rows per friendship, one for each side.
CREATE TABLE friendships (
    user_id    text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    friend_id  text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, friend_id),
    CHECK (user_id <> friend_id)
);

CREATE INDEX friendships_friend_id ON friendships (friend_id);

CREATE TABLE blocks (
    blocker_id text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE INDEX blocks_blocked_id ON blocks (blocked_id);

-- Groups of friends. Deleting an account hands its groups over first (BeforeAccountDeletion); the cascade only
-- catches what is left.
CREATE TABLE user_groups (
    id         text        PRIMARY KEY,
    name       text        NOT NULL,
    owner_id   text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL
);

CREATE INDEX user_groups_owner_id ON user_groups (owner_id);

CREATE TABLE group_members (
    group_id  text        NOT NULL REFERENCES user_groups (id) ON DELETE CASCADE,
    user_id   text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX group_members_user_id ON group_members (user_id);

-- Reported chat messages, kept 90 days. No foreign keys: the report outlives the game (in memory only) and stays
-- when either account is deleted. Reporting the same message twice is a no-op (reports_once).
CREATE TABLE reports (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id            text        NOT NULL,
    message_seq        bigint      NOT NULL,
    reporter_player_id text        NOT NULL,
    reporter_user_id   text,
    reported_user_id   text,
    reported_name      text        NOT NULL,
    text               text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT reports_once UNIQUE (game_id, message_seq, reporter_player_id)
);

CREATE INDEX reports_created_at ON reports (created_at);
