-- The field log (docs/adr/0018-field-test-build.md §3.1): a real game's lab log is a run of kind GAME, opened when the
-- first phone of the game joins it and finished when the game is gone from the server; its devices are the game's
-- players who agreed (consent_at), with their account where they have one (user_id: the device and its chunks go
-- with the account, ON DELETE CASCADE; guests have none). Only on a server with the feature FIELD_LOG (the test
-- server). Coordinates only in a GAME run's chunks. A GAME run goes whole (devices, chunks, report) 90 days after its
-- end (DataRetention); a LAB run keeps its run, devices and report as before.
ALTER TABLE lab_runs ADD COLUMN kind text NOT NULL DEFAULT 'LAB';
ALTER TABLE lab_runs ADD COLUMN game_id text;
-- One field run per game (game ids are random and never come back).
CREATE UNIQUE INDEX lab_runs_game ON lab_runs (game_id) WHERE game_id IS NOT NULL;
-- The janitor's question every minute: the field runs still open.
CREATE INDEX lab_runs_open_games ON lab_runs (kind) WHERE kind = 'GAME' AND finished_at IS NULL;

ALTER TABLE lab_devices ADD COLUMN user_id text REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE lab_devices ADD COLUMN consent_at timestamptz;
CREATE INDEX lab_devices_user ON lab_devices (user_id) WHERE user_id IS NOT NULL;
