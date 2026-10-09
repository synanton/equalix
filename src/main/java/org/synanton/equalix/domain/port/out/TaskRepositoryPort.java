package org.synanton.equalix.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.QueuedLeaf;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;

/** Outgoing port for persisting and querying tasks. */
public interface TaskRepositoryPort {

    Task save(Task task);

    /**
     * Persists a brand-new task with a single INSERT. Unlike {@link #save}, this never
     * issues a SELECT first: Spring Data's merge path treats the primitive {@code version = 0}
     * as detached and costs a SELECT-miss per ingest. Only for rows known absent.
     */
    Task insert(Task task);

    Optional<Task> findById(UUID id);

    List<Task> findByStatus(TaskStatus status, int limit);

    /**
     * Selects QUEUED tasks ordered by priority using SELECT FOR UPDATE SKIP LOCKED
     * to allow safe concurrent dispatch across multiple service instances.
     *
     * @param limit maximum number of tasks to lock and return
     * @param maxPerClient per-client hard quota; null disables quota enforcement
     */
    List<Task> findAndLockDispatchable(int limit, @Nullable Integer maxPerClient);

    /**
     * Same eligibility and locking as {@link #findAndLockDispatchable}, but returns the oldest tasks first.
     * Used to build the aging candidate pool.
     */
    List<Task> findAndLockOldestDispatchable(int limit, @Nullable Integer maxPerClient);

    /** Dispatchable backlog (QUEUED, non-sequential) per fairness key, for hierarchical selection. */
    List<QueuedLeaf> findQueuedLeaves();

    /**
     * Locks up to the given number of QUEUED non-sequential tasks per fairness key, best
     * {@code (priority, created_at, id)} first, skipping rows locked by others.
     *
     * @return tasks grouped by key in the order of {@code limitsByKey}, each group best first
     */
    List<Task> findAndLockQueuedHeads(Map<String, Integer> limitsByKey);

    /** Finds tasks that have been in QUEUED status longer than the given threshold. */
    List<Task> findStarvedTasks(long olderThanMs, int limit);

    Optional<Task> findNextSequentialTask(String fairnessKey, long sequenceNumber, TaskStatus status);

    /** Finds sequential tasks waiting for their predecessor result to be attached. */
    List<Task> findTasksWaitingForPreviousResult();

    List<Task> findByFairnessKey(String fairnessKey, @Nullable TaskStatus status);

    int updateStatusBatch(List<UUID> ids, TaskStatus newStatus);

    /**
     * Marks locked QUEUED tasks DISPATCHED in a single UPDATE (no entity round-trip).
     * The caller must hold the rows (SELECT FOR UPDATE SKIP LOCKED).
     *
     * @return number of rows transitioned
     */
    int bulkMarkDispatched(List<UUID> ids);

    /**
     * Marks one QUEUED task DISPATCHED, attaching the predecessor result for sequential tasks.
     *
     * @return true if the row transitioned, false if it already moved (concurrent transition)
     */
    boolean markDispatched(UUID id, @Nullable byte[] previousResult);

    /**
     * Assigns queueing state (status, priority, virtual finish tag) to one RECEIVED task.
     * Replaces the load-modify-save round-trip on the priority-calc hot path.
     */
    void markQueued(UUID id, long priority, @Nullable Double virtualFinish);

    /**
     * Completes one in-flight task atomically: the status guard and the version check run
     * in the UPDATE itself, folding the load-modify-save round-trip into one statement.
     *
     * @return true if the row transitioned; false if it was already terminal or concurrently modified
     */
    boolean completeTask(UUID id, long version, TaskStatus status, @Nullable byte[] result,
        @Nullable String error, Instant completedAt);

    /**
     * Records remote-executor acceptance (DISPATCHED → COMMITTED). No-op when the task
     * already moved on; the ack arrives asynchronously and must never overwrite progress.
     *
     * @return true if the row transitioned
     */
    boolean markCommitted(UUID id);

    /**
     * Expires one in-flight task (→ TIMEOUT). Like {@link #completeTask}, the guards run
     * in the UPDATE; a concurrent transition yields false instead of clobbering it.
     *
     * @return true if the row transitioned
     */
    boolean markTimeout(UUID id, long version, String error, Instant completedAt);

    /**
     * Promotes all starved QUEUED tasks (priority → 0) in a single UPDATE instead of
     * one load-modify-save per task.
     *
     * @return number of rows promoted
     */
    int bulkPromoteStarvedTasks(long olderThanMs, int limit);

    /** Counts DISPATCHED and COMMITTED tasks grouped by fairness key. */
    Map<String, Integer> countInFlightByFairnessKey();

    /** In-flight tasks whose last update is older than the timeout. */
    List<Task> findTimedOutInFlight(long olderThanMs, int limit);
}
