-- The leaderboard (docs/adr/0020-leaderboard.md) reads everybody's results of this week and last week by their end;
-- nothing new is stored, only this index for that range.
CREATE INDEX game_results_finished_at ON game_results (finished_at);
