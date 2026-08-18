package org.synanton.equalix.domain.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;

/** Outgoing port for persisting and querying tasks. */
public interface TaskRepositoryPort {

    Task save(Task task);

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

    /** Finds tasks that have been in QUEUED status longer than the given threshold. */
    List<Task> findStarvedTasks(long olderThanMs, int limit);

    Optional<Task> findNextSequentialTask(String fairnessKey, long sequenceNumber, TaskStatus status);

    /** Finds sequential tasks waiting for their predecessor result to be attached. */
    List<Task> findTasksWaitingForPreviousResult();

    List<Task> findByFairnessKey(String fairnessKey, @Nullable TaskStatus status);

    int updateStatusBatch(List<UUID> ids, TaskStatus newStatus);

    /** Counts DISPATCHED and COMMITTED tasks grouped by fairness key. */
    Map<String, Integer> countInFlightByFairnessKey();

    /** In-flight tasks whose last update is older than the timeout. */
    List<Task> findTimedOutInFlight(long olderThanMs, int limit);
}
