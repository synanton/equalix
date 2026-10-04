-- EQX-3: persistent weighted virtual time (T_k).
--
-- client_virtual_time.virtual_time   : T_k, the key's accumulated service position. Advanced on dispatch.
-- client_virtual_time.virtual_finish : finish tag of the last task queued for the key (T_k plus the virtual
--                                      cost of tasks still waiting). Advanced when a task is queued.
-- scheduler_virtual_clock            : system virtual time V, the highest finish tag dispatched so far.
--                                      Keys that were idle start at V so they do not bank credit.

CREATE TABLE client_virtual_time (
    fairness_key   VARCHAR(255) PRIMARY KEY,
    virtual_time   DOUBLE PRECISION NOT NULL DEFAULT 0,
    virtual_finish DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE scheduler_virtual_clock (
    id           SMALLINT PRIMARY KEY CHECK (id = 1),
    virtual_time DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tasks queued before this migration carry epoch-millisecond priorities. Starting V above them keeps
-- those tasks ahead of newly tagged work, so the backlog drains in its original order.
INSERT INTO scheduler_virtual_clock (id, virtual_time)
SELECT 1, COALESCE(MAX(priority), 0)
FROM tasks
WHERE status = 'QUEUED';

ALTER TABLE tasks
    ADD COLUMN virtual_finish DOUBLE PRECISION;
