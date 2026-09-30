-- The radio lab's runs (docs/adr/0017-radar-techniques-and-big-run.md §7): test phones of the debug build, no players,
-- no accounts, no positions. RSSI, tokens and raw frames of the lab's own devices only. Chunks are deleted 90 days
-- after the run's end (DataRetention); runs and reports (labels, models, numbers) stay. Everything goes with the run.
-- created_by_name: the admin's nickname then, as in the audit log, and like it cleared a year after the run was made;
-- no user id, so nothing ties a run to an account.
CREATE TABLE lab_runs (
    id               text        PRIMARY KEY,
    code             text        NOT NULL UNIQUE,
    title            text        NOT NULL,
    scenario_id      text        NOT NULL,
    scenario_version integer     NOT NULL,
    status           text        NOT NULL,
    step_index       integer     NOT NULL DEFAULT -1,
    step_started_at  timestamptz,
    paused_at        timestamptz,
    revision         bigint      NOT NULL DEFAULT 0,
    created_by_name  text,
    created_at       timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    salt             text        NOT NULL
);
CREATE INDEX lab_runs_created_at ON lab_runs (created_at);

CREATE TABLE lab_devices (
    id            text        PRIMARY KEY,
    run_id        text        NOT NULL REFERENCES lab_runs (id) ON DELETE CASCADE,
    label         text        NOT NULL,
    model         text,
    os            text,
    build         text,
    commit        text,
    capabilities  text        NOT NULL,            -- LabCapabilities as JSON
    token_hash    text        NOT NULL UNIQUE,     -- SHA-256 of the device token, never the token
    radar_token   text        NOT NULL,
    joined_at     timestamptz NOT NULL,
    last_chunk_at timestamptz,
    last_seq      bigint,
    bytes         bigint      NOT NULL DEFAULT 0,  -- stored (gzip) bytes of its chunks
    events        bigint      NOT NULL DEFAULT 0
);
CREATE INDEX lab_devices_run ON lab_devices (run_id);

CREATE TABLE lab_chunks (
    id          bigserial   PRIMARY KEY,
    device_id   text        NOT NULL REFERENCES lab_devices (id) ON DELETE CASCADE,
    seq_from    bigint      NOT NULL,
    seq_to      bigint      NOT NULL,
    t_from      bigint,
    t_to        bigint,
    events      integer     NOT NULL,
    received_at timestamptz NOT NULL,
    body        bytea       NOT NULL,              -- gzip JSONL
    UNIQUE (device_id, seq_from)
);

CREATE TABLE lab_reports (
    run_id      text        PRIMARY KEY REFERENCES lab_runs (id) ON DELETE CASCADE,
    version     integer     NOT NULL,
    computed_at timestamptz NOT NULL,
    body        text        NOT NULL               -- LabReport as JSON
);
