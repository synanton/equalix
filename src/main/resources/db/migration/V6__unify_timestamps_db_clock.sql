-- Unify timestamp source: all updated_at writes are DB-assigned.
--
-- Process-stamped updated_at writes (Instant.now/clock in application code)
-- skew against SQL-now() reads (threshold scans, timeout sweep) whenever an
-- app host's clock drifts from the DB host. A single BEFORE UPDATE trigger
-- stamps updated_at from the DB clock on every write path — application,
-- psql, future tools — eliminating the skew class entirely rather than
-- bounding it. Mirrors Go migration 00005 (same trigger, same tables).
-- created_at/completed_at/blocked_at are semantic fields, unchanged:
-- created_at keeps DEFAULT now(); completed_at/blocked_at record events.

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

DO $$
DECLARE tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'tasks', 'client_counts', 'client_sequence_state',
        'client_virtual_time', 'scheduler_virtual_clock', 'hierarchy_node'
    ] LOOP
        EXECUTE format(
            'DROP TRIGGER IF EXISTS trg_set_updated_at ON %I; ' ||
            'CREATE TRIGGER trg_set_updated_at BEFORE INSERT OR UPDATE ON %I ' ||
            'FOR EACH ROW EXECUTE FUNCTION set_updated_at()',
            tbl, tbl);
    END LOOP;
END;
$$;
