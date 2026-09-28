-- Staff roles, the staff login with an authenticator app, admin sessions, bans and chat bans, how reports were handled,
-- and the audit log (docs/adr/0008-admin.md). Times come from the server's Clock, as everywhere.

-- ADMIN is only ever set on the server (docs/deploy.md); admins make moderators in the admin.
ALTER TABLE users ADD COLUMN role text NOT NULL DEFAULT 'PLAYER' CHECK (role IN ('PLAYER', 'MODERATOR', 'ADMIN'));

CREATE INDEX users_staff ON users (role) WHERE role <> 'PLAYER';

-- Setting up the authenticator needs a code sent to the staff member's confirmed email.
ALTER TABLE email_codes DROP CONSTRAINT email_codes_purpose_check;
ALTER TABLE email_codes ADD CONSTRAINT email_codes_purpose_check
    CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'STAFF_ENROLL'));

-- A staff member's TOTP secret, AES-GCM encrypted with hovanki.admin.secret-key. last_step: the time step of the last
-- accepted code, so no code works twice.
CREATE TABLE staff_totp (
    user_id      text        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret       text        NOT NULL,
    last_step    bigint      NOT NULL,
    confirmed_at timestamptz NOT NULL
);

-- Admin sessions (the /admin page), separate from the app's account sessions; only the SHA-256 of the token is stored.
-- Idle and absolute limits: hovanki.admin.session-idle / session-max; DataRetention deletes the old ones.
CREATE TABLE admin_sessions (
    token_hash   text        PRIMARY KEY,
    user_id      text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz NOT NULL
);

CREATE INDEX admin_sessions_user_id ON admin_sessions (user_id);

-- Bans (no login, no game with the account, no chat) and chat bans. until NULL: forever; lifted_at: lifted early.
-- Go with the account; ended ones are deleted a year after their end (DataRetention).
CREATE TABLE sanctions (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       text        NOT NULL CHECK (kind IN ('BAN', 'MUTE')),
    reason     text        NOT NULL,
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL,
    until      timestamptz,
    lifted_at  timestamptz,
    lifted_by  text
);

CREATE INDEX sanctions_user_id ON sanctions (user_id);

-- How a report was handled; the report itself is still deleted after 90 days.
ALTER TABLE reports ADD COLUMN resolved_at timestamptz;
ALTER TABLE reports ADD COLUMN resolved_by text;
ALTER TABLE reports ADD COLUMN resolution text;

CREATE INDEX reports_open ON reports (id) WHERE resolved_at IS NULL;
CREATE INDEX reports_reported_user_id ON reports (reported_user_id);

-- Everything staff did. No foreign keys: the log outlives the accounts it mentions; deleted after a year.
CREATE TABLE admin_audit (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at             timestamptz NOT NULL,
    actor_id       text        NOT NULL,
    actor_name     text        NOT NULL,
    action         text        NOT NULL,
    target_user_id text,
    target         text,
    reason         text
);

CREATE INDEX admin_audit_at ON admin_audit (at);
