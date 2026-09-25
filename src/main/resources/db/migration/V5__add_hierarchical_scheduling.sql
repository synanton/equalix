-- EQX-7: hierarchical virtual-time scheduling.
--
-- hierarchy_node.node_key              : fairness key (leaf), path plus separator (internal node) or '' (root).
-- hierarchy_node.virtual_time          : service received, in the parent's virtual time (CFS vruntime).
-- hierarchy_node.children_virtual_time : floor for the node's children; idle children restart here.

CREATE TABLE hierarchy_node (
    node_key              VARCHAR(255) PRIMARY KEY,
    virtual_time          DOUBLE PRECISION NOT NULL DEFAULT 0,
    children_virtual_time DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Per-key backlog aggregation and ordered per-key head selection for the hierarchical dispatcher.
CREATE INDEX idx_tasks_queued_by_key
    ON tasks (fairness_key, priority, created_at, id)
    INCLUDE (weight)
    WHERE status = 'QUEUED'
      AND is_sequential = FALSE;
