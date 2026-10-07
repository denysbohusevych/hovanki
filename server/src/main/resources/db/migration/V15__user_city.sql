-- The city of the city leaderboard (docs/adr/0022-city-leaderboard.md): the player's own choice, one of the app's
-- fixed list (Cities.IDS), never taken from a position. Null: not chosen. It lives on the account and goes with it.
ALTER TABLE users ADD COLUMN city text;
