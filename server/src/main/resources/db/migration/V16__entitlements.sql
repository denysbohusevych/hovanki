-- Paid extras of an account (docs/adr/0023-entitlements.md): one row per account and extra while it is active. No
-- payments yet: admins grant them in the admin (source ADMIN); the audit log keeps who granted or took one and why.
-- Personal data: goes with the account, and DataRetention deletes a row once it has ended.
CREATE TABLE entitlements (
    user_id     text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    entitlement text        NOT NULL,
    source      text        NOT NULL,
    granted_at  timestamptz NOT NULL,
    -- null: forever
    until       timestamptz,
    -- the admin's nickname as it was, like sanctions.created_by
    granted_by  text,
    PRIMARY KEY (user_id, entitlement)
);
