-- Baseline schema (squashed V1-V6). Fresh projects only: there is no upgrade path
-- from the old V1..V6 chain, which never ran in production.
--
-- Design notes:
-- - tasks.weight is CHECKed positive; CreateTaskUseCase validates the same invariant.
-- - depends_on_task_id intentionally has no FK: successors may be ingested before
--   the predecessor row exists; ResultPassthroughRecoveryService reconciles them.
-- - scheduler_virtual_clock starts at V = 0 (single seed row). No legacy backfill:
--   pre-virtual-time epoch-millisecond priorities no longer exist to drain.
-- - All updated_at columns are DB-assigned by trg_set_updated_at (see below), so
--   application writers must not set them. On INSERT an explicit non-NULL value
--   (backfills, tests) is kept; on UPDATE the DB clock always wins.
-- - ddl-auto is validate: every column here must match the JPA entities.

CREATE TYPE task_status AS ENUM (
    'RECEIVED',
    'QUEUED',
    'DISPATCHED',
    'COMMITTED',
    'SUCCEEDED',
    'FAILED',
    'TIMEOUT'
);

CREATE TABLE tasks (
    id                      UUID PRIMARY KEY,
    fairness_key            VARCHAR(255) NOT NULL,
    weight                  DECIMAL(10, 4) NOT NULL DEFAULT 1.0 CHECK (weight > 0),
    status                  task_status NOT NULL DEFAULT 'RECEIVED',
    priority                BIGINT,
    virtual_finish          DOUBLE PRECISION,
    payload                 BYTEA NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at            TIMESTAMP WITH TIME ZONE,
    retry_count             INT NOT NULL DEFAULT 0,
    last_error              TEXT,
    result                  BYTEA,
    version                 BIGINT NOT NULL DEFAULT 0,
    sequence_number         BIGINT,
    depends_on_task_id      UUID,
    is_sequential           BOOLEAN NOT NULL DEFAULT FALSE,
    previous_result         BYTEA,
    requires_previous_result BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_tasks_status_priority ON tasks (status, priority);
CREATE INDEX idx_tasks_status_created_at ON tasks (status, created_at);
CREATE INDEX idx_tasks_fairness_key_created_at ON tasks (fairness_key, created_at);

-- Hierarchical dispatcher backlog aggregation and per-key head selection.
CREATE INDEX idx_tasks_queued_by_key
    ON tasks (fairness_key, priority, created_at, id)
    INCLUDE (weight)
    WHERE status = 'QUEUED'
      AND is_sequential = FALSE;

-- Watchdog reconciliation (GROUP BY fairness_key over in-flight rows).
CREATE INDEX idx_tasks_in_flight_by_key
    ON tasks (fairness_key)
    WHERE status IN ('DISPATCHED', 'COMMITTED');

-- Timeout sweep (ORDER BY updated_at over in-flight rows).
CREATE INDEX idx_tasks_in_flight_updated_at
    ON tasks (updated_at)
    WHERE status IN ('DISPATCHED', 'COMMITTED');

CREATE INDEX idx_tasks_sequential_dispatch
    ON tasks (fairness_key, sequence_number, status)
    WHERE is_sequential = TRUE;

CREATE INDEX idx_tasks_next_in_sequence
    ON tasks (fairness_key, sequence_number)
    WHERE status = 'QUEUED'
      AND is_sequential = TRUE;

CREATE TABLE client_counts (
    fairness_key    VARCHAR(255) PRIMARY KEY,
    in_flight_count INT NOT NULL DEFAULT 0 CHECK (in_flight_count >= 0),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE client_sequence_state (
    fairness_key              VARCHAR(255) PRIMARY KEY,
    last_completed_sequence   BIGINT NOT NULL DEFAULT 0,
    last_dispatched_sequence  BIGINT NOT NULL DEFAULT 0,
    current_executing_task_id UUID,
    is_blocked                BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_at                TIMESTAMP WITH TIME ZONE,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Persistent weighted virtual time (T_k per key; finish tag of the last queued task).
CREATE TABLE client_virtual_time (
    fairness_key   VARCHAR(255) PRIMARY KEY,
    virtual_time   DOUBLE PRECISION NOT NULL DEFAULT 0,
    virtual_finish DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- System virtual time V (single row, id = 1). Starts at 0: idle keys restart here
-- instead of banking credit.
CREATE TABLE scheduler_virtual_clock (
    id           SMALLINT PRIMARY KEY CHECK (id = 1),
    virtual_time DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO scheduler_virtual_clock (id, virtual_time) VALUES (1, 0);

-- Hierarchical scheduling node state (EQX-7). node_key is the leaf fairness key,
-- an internal path with trailing separator, or '' for the root.
CREATE TABLE hierarchy_node (
    node_key              VARCHAR(255) PRIMARY KEY,
    virtual_time          DOUBLE PRECISION NOT NULL DEFAULT 0,
    children_virtual_time DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- Unify timestamp source: updated_at is DB-assigned on every write path, so
-- threshold scans (starvation, timeout sweep) never skew against app-host clocks.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    -- UPDATE: unconditional DB clock (no legitimate writer sets this).
    -- INSERT: fill only when NULL, so backfills and tests inserting
    -- explicit timestamps keep working without disabling the trigger.
    IF TG_OP = 'UPDATE' OR NEW.updated_at IS NULL THEN
        NEW.updated_at = now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON client_counts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON client_sequence_state
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON client_virtual_time
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON scheduler_virtual_clock
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON hierarchy_node
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
