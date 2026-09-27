-- Confirming the email is optional now: DataRetention no longer deletes unconfirmed accounts, nothing uses this index.
DROP INDEX IF EXISTS users_unverified_created_at;
