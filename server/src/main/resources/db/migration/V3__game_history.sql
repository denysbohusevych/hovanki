-- Game history, statistics and saved routes (docs/adr/0007-game-history-and-routes.md). Games still run in memory
-- only; these rows are written once per game, after it finished, outside the game's lock and off the request thread.

-- Consent to keep the routes of one's games: when "save my routes" was turned on; null: off (the default). Turning it
-- off deletes the saved routes (HistoryService).
ALTER TABLE users ADD COLUMN save_routes_since timestamptz;

-- Every game that was played to its end, with or without accounts: numbers about the game, nothing about any player
-- (no names, no accounts, no coordinates: the zone's size, not its place). Kept for the game's analytics.
CREATE TABLE played_games (
    id                 text             PRIMARY KEY,
    created_at         timestamptz      NOT NULL,
    started_at         timestamptz      NOT NULL,
    zone_started_at    timestamptz,
    finished_at        timestamptz      NOT NULL,
    players            integer          NOT NULL,
    guests             integer          NOT NULL,
    seekers            integer          NOT NULL,
    hiders_caught      integer          NOT NULL,
    hiders_eliminated  integer          NOT NULL,
    catch_claims       integer          NOT NULL,
    catches            integer          NOT NULL,
    disputes           integer          NOT NULL,
    chat_messages      integer          NOT NULL,
    buildings          text             NOT NULL,
    zone_radius_meters double precision NOT NULL,
    zone_stages        integer          NOT NULL,
    hiding_seconds     integer          NOT NULL,
    seeking_seconds    integer          NOT NULL
);

CREATE INDEX played_games_finished_at ON played_games (finished_at);

-- How a game went for each player with an account: one row per account and game, until the account is deleted.
-- Numbers only; the distance, moving time and top speed are computed from the player's fixes in memory
-- (RouteRecorder), the fixes themselves are not kept here.
CREATE TABLE game_results (
    user_id           text             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    game_id           text             NOT NULL REFERENCES played_games (id),
    started_at        timestamptz      NOT NULL,
    finished_at       timestamptz      NOT NULL,
    role              text             NOT NULL CHECK (role IN ('HIDER', 'SEEKER')),
    status            text             NOT NULL CHECK (status IN ('ACTIVE', 'CAUGHT', 'ELIMINATED')),
    won               boolean          NOT NULL,
    players           integer          NOT NULL,
    seekers           integer          NOT NULL,
    catch_claims      integer          NOT NULL,
    catches           integer          NOT NULL,
    -- A hider: from the start of seeking until caught, eliminated or the end. Null for seekers.
    survived_seconds  integer,
    zone_warnings     integer          NOT NULL,
    building_warnings integer          NOT NULL,
    -- Accepted GPS fixes during the round: how well the phone reported.
    fixes             integer          NOT NULL,
    distance_meters   double precision NOT NULL,
    moving_seconds    integer          NOT NULL,
    max_speed_mps     double precision,
    PRIMARY KEY (user_id, game_id)
);

CREATE INDEX game_results_user_finished_at ON game_results (user_id, finished_at DESC);
CREATE INDEX game_results_game_id ON game_results (game_id);

-- Saved routes: only of accounts with save_routes_since set when the game was saved, deleted after
-- hovanki.history.route-retention (90 days, DataRetention), when their owner turns saving off or deletes the route,
-- and with the account. Only their owner reads them through the API.
CREATE TABLE game_routes (
    user_id         text        NOT NULL,
    game_id         text        NOT NULL,
    saved_at        timestamptz NOT NULL,
    role            text        NOT NULL CHECK (role IN ('HIDER', 'SEEKER')),
    zone            jsonb       NOT NULL,
    started_at      timestamptz NOT NULL,
    zone_started_at timestamptz,
    finished_at     timestamptz NOT NULL,
    -- [{"lat":..,"lon":..,"accuracyMeters":..,"atMillis":..}, ...], oldest first (RoutePoint).
    points          jsonb       NOT NULL,
    PRIMARY KEY (user_id, game_id),
    FOREIGN KEY (user_id, game_id) REFERENCES game_results (user_id, game_id) ON DELETE CASCADE
);

CREATE INDEX game_routes_saved_at ON game_routes (saved_at);
