ALTER TABLE tasks
    ADD COLUMN sequence_number          BIGINT,
    ADD COLUMN depends_on_task_id       UUID,
    ADD COLUMN is_sequential            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN previous_result          BYTEA,
    ADD COLUMN requires_previous_result BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE client_sequence_state (
    fairness_key              VARCHAR(255) PRIMARY KEY,
    last_completed_sequence   BIGINT NOT NULL DEFAULT 0,
    last_dispatched_sequence  BIGINT NOT NULL DEFAULT 0,
    current_executing_task_id UUID,
    is_blocked                BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_at                TIMESTAMP WITH TIME ZONE,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_sequential_dispatch
    ON tasks (fairness_key, sequence_number, status)
    WHERE is_sequential = TRUE;

CREATE INDEX idx_tasks_next_in_sequence
    ON tasks (fairness_key, sequence_number)
    WHERE status = 'QUEUED'
      AND is_sequential = TRUE;
