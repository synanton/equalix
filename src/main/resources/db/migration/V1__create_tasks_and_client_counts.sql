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
    id               UUID PRIMARY KEY,
    fairness_key     VARCHAR(255) NOT NULL,
    weight           DECIMAL(10, 4) NOT NULL DEFAULT 1.0,
    status           task_status NOT NULL DEFAULT 'RECEIVED',
    priority         BIGINT,
    payload          BYTEA NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at     TIMESTAMP WITH TIME ZONE,
    retry_count      INT NOT NULL DEFAULT 0,
    last_error       TEXT,
    result           BYTEA,
    version          BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_tasks_status_priority ON tasks (status, priority);
CREATE INDEX idx_tasks_status_created_at ON tasks (status, created_at);
CREATE INDEX idx_tasks_fairness_key ON tasks (fairness_key);

CREATE TABLE client_counts (
    fairness_key    VARCHAR(255) PRIMARY KEY,
    in_flight_count INT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
