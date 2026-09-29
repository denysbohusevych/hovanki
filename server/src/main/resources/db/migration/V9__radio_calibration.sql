-- How loud each kind of phone hears each other kind over Bluetooth (docs/adr/0012-nearby-radar.md, «Калибровка»):
-- the radar's readings of finished games, counted by the two phones' models, where they were (in the hand, in the
-- pocket), the anchor (every reading; the phones far apart by GPS; the seconds before a confirmed catch, when they
-- stood next to each other) and the signal in dBm. Aggregates only: no game, no player, no position. Not personal
-- data; kept.
CREATE TABLE radio_calibration (
    hearer_model text        NOT NULL,
    heard_model  text        NOT NULL,
    hearer_carry text        NOT NULL,
    heard_carry  text        NOT NULL,
    anchor       text        NOT NULL,
    rssi_dbm     smallint    NOT NULL,
    readings     bigint      NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (hearer_model, heard_model, hearer_carry, heard_carry, anchor, rssi_dbm)
);
