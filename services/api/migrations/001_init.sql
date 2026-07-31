-- Attrape-Lettres API, initial schema.
--
-- Three tables, no relationships between them, and no column anywhere that
-- could name a person. That is the design, not a starting point: children's
-- first names are stripped on the device before upload (`toWire`), so there is
-- nothing here to protect because there is nothing here to leak.

-- One household = one document. The server never looks inside it: the merge
-- happens on the devices, and `revision` is the whole of the server's
-- contribution — it is what makes `If-Match` able to refuse a write built on a
-- superseded read.
CREATE TABLE IF NOT EXISTS household (
    id         text PRIMARY KEY,
    doc        jsonb       NOT NULL,
    revision   bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Append-only. No device id, no household id, no session id — there is
-- deliberately nothing to group these rows by, which is what keeps them
-- anonymous rather than pseudonymous.
CREATE TABLE IF NOT EXISTS telemetry_event (
    id          bigserial PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now(),
    app_version text        NOT NULL,
    event       text        NOT NULL,
    props       jsonb       NOT NULL
);

CREATE INDEX IF NOT EXISTS telemetry_event_received_at_idx
    ON telemetry_event (received_at);
CREATE INDEX IF NOT EXISTS telemetry_event_event_idx
    ON telemetry_event (event);

-- `where_` because WHERE is reserved. `message` and `stack` are the only
-- free-form strings this database accepts anywhere; the client never attaches
-- app state and both ends truncate.
CREATE TABLE IF NOT EXISTS telemetry_error (
    id          bigserial PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now(),
    app_version text        NOT NULL,
    where_      text        NOT NULL,
    message     text        NOT NULL,
    stack       text        NOT NULL
);

CREATE INDEX IF NOT EXISTS telemetry_error_received_at_idx
    ON telemetry_error (received_at);
