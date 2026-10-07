-- Achievements (docs/adr/0021-achievements.md) are computed from game_results on every request; only the time the
-- player last looked at them is stored, to tell which levels are new. Goes with the account.
ALTER TABLE users ADD COLUMN achievements_seen_at timestamptz;
