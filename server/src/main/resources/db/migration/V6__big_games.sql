-- Big games (docs/adr/0010-big-games.md): an admin schedules one with a drawn zone and a start time, players sign up
-- ahead. The schedule and the sign-ups are here to outlive a server update; the round itself stays in memory like every
-- game. Times come from the server's Clock.

-- zone: the drawn figure (ZonePolygon as JSON), a public meeting place chosen by staff, nobody's position. setup: the
-- round's times, glow and seekers (BigGameSetup); norms: m² a player by kind of ground (AreaNorms); capacity and areas:
-- the estimate at the last change (null: no map data). game_id: the round in memory once the lobby opened.
-- host_user_id: the admin running it, whose account can't play in it. created_by: the admin's nickname then, as in
-- sanctions. Deleted with the sign-ups 90 days after the end (DataRetention).
CREATE TABLE big_games (
    id           text        PRIMARY KEY,
    title        text        NOT NULL,
    status       text        NOT NULL
        CHECK (status IN ('SCHEDULED', 'LOBBY', 'RUNNING', 'FINISHED', 'CANCELLED', 'INTERRUPTED')),
    starts_at    timestamptz NOT NULL,
    time_zone    text        NOT NULL,
    zone         jsonb       NOT NULL,
    setup        jsonb       NOT NULL,
    norms        jsonb       NOT NULL,
    capacity     integer,
    areas        jsonb,
    player_limit integer     NOT NULL,
    game_id      text,
    host_user_id text        REFERENCES users (id) ON DELETE SET NULL,
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    ended_at     timestamptz
);

CREATE INDEX big_games_open ON big_games (starts_at) WHERE status IN ('SCHEDULED', 'LOBBY', 'RUNNING');
CREATE INDEX big_games_ended_at ON big_games (ended_at);

-- Who signed up for which big game: personal (who plans to be where and when), so it goes with the account and with
-- the big game.
CREATE TABLE big_game_signups (
    big_game_id  text        NOT NULL REFERENCES big_games (id) ON DELETE CASCADE,
    user_id      text        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    signed_up_at timestamptz NOT NULL,
    PRIMARY KEY (big_game_id, user_id)
);

CREATE INDEX big_game_signups_user_id ON big_game_signups (user_id);
