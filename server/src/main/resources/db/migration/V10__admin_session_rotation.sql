-- Admin sessions last longer and change their token as they are used (docs/adr/0008-admin.md, «Изменение 2026-09-29»).
-- rotated_at: when the token (the cookie) was last replaced; the first request hovanki.admin.session-rotate after that
-- replaces it again. previous_hash: the replaced token, which still works until previous_until for the requests already
-- on their way; after that it ends the session (somebody else has the token). Only SHA-256 hashes, as before.
ALTER TABLE admin_sessions ADD COLUMN rotated_at timestamptz;
UPDATE admin_sessions SET rotated_at = created_at;
ALTER TABLE admin_sessions ALTER COLUMN rotated_at SET NOT NULL;

ALTER TABLE admin_sessions ADD COLUMN previous_hash text UNIQUE;
ALTER TABLE admin_sessions ADD COLUMN previous_until timestamptz;
