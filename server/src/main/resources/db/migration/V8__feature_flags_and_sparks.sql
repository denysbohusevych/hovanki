-- Server features the operator turns on in the admin (docs/adr/0012-nearby-radar.md, docs/adr/0013-quests-sparks-and-
-- sensors.md): one row per feature ever switched, by name; a feature without a row is off. Not personal data.
CREATE TABLE feature_flags (
    feature    text        PRIMARY KEY,
    enabled    boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by text        NOT NULL
);

-- Sparks left at the end and quests done, numbers like the rest of the row (docs/adr/0013, no coordinates).
ALTER TABLE game_results ADD COLUMN sparks integer NOT NULL DEFAULT 0;
ALTER TABLE game_results ADD COLUMN quests_done integer NOT NULL DEFAULT 0;
