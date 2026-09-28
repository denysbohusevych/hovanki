-- Recordings of finished games (docs/adr/0011-spectators-and-recordings.md): everybody's way through the round, to
-- watch again from the history. Only the game's players with an account read them (HistoryService), nobody else, staff
-- included. Kept 90 days (hovanki.history.recording-retention, DataRetention); saved only when at least one player had
-- an account.
CREATE TABLE game_recordings (
    game_id         text        PRIMARY KEY REFERENCES played_games (id) ON DELETE CASCADE,
    saved_at        timestamptz NOT NULL,
    started_at      timestamptz NOT NULL,
    zone_started_at timestamptz,
    finished_at     timestamptz NOT NULL,
    -- The zone schedule (ZoneSchedule) and, for a zone by streets, one polygon per stage (ZonePolygon).
    zone            jsonb       NOT NULL,
    street_zone     jsonb
);

CREATE INDEX game_recordings_saved_at ON game_recordings (saved_at);

-- One row per player of a recorded game, guests too: the name they played under, their role, how the round ended for
-- them and their way ([{"lat":..,"lon":..,"atMillis":..}, ...], TrackPoint: accurate fixes, thinned). A player with an
-- account takes their row with them when they delete the account.
CREATE TABLE game_recording_tracks (
    game_id   text        NOT NULL REFERENCES game_recordings (game_id) ON DELETE CASCADE,
    player_id text        NOT NULL,
    user_id   text        REFERENCES users (id) ON DELETE CASCADE,
    name      text        NOT NULL,
    role      text        NOT NULL CHECK (role IN ('HIDER', 'SEEKER')),
    status    text        NOT NULL CHECK (status IN ('ACTIVE', 'CAUGHT', 'ELIMINATED')),
    out_at    timestamptz,
    caught_by text,
    points    jsonb       NOT NULL,
    PRIMARY KEY (game_id, player_id)
);

CREATE INDEX game_recording_tracks_user ON game_recording_tracks (user_id);
