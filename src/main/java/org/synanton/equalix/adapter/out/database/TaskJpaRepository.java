package org.synanton.equalix.adapter.out.database;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.TaskStatus;

public interface TaskJpaRepository extends JpaRepository<TaskEntity, UUID> {

    List<TaskEntity> findByStatusOrderByCreatedAtAsc(TaskStatus status, org.springframework.data.domain.Pageable pageable);

    List<TaskEntity> findByFairnessKeyOrderByCreatedAtAsc(String fairnessKey);

    List<TaskEntity> findByFairnessKeyAndStatusOrderByCreatedAtAsc(String fairnessKey, TaskStatus status);

    @Query(value = """
    SELECT t.*
    FROM tasks t
    LEFT JOIN client_counts cc
        ON t.fairness_key = cc.fairness_key
    WHERE t.status = 'QUEUED'
      AND t.is_sequential = false
      AND (
            :maxPerClient IS NULL
         OR cc.in_flight_count < :maxPerClient
         OR cc.in_flight_count IS NULL
         OR t.priority <= 0
      )
    ORDER BY t.priority ASC NULLS LAST, t.created_at ASC, t.id ASC
    LIMIT :limit
    FOR UPDATE OF t SKIP LOCKED
    """, nativeQuery = true)
    List<TaskEntity> findAndLockDispatchable(
            @Param("limit") int limit,
            @Param("maxPerClient") Integer maxPerClient
    );

    @Query(value = """
    SELECT t.*
    FROM tasks t
    LEFT JOIN client_counts cc
        ON t.fairness_key = cc.fairness_key
    WHERE t.status = 'QUEUED'
      AND t.is_sequential = false
      AND (
            :maxPerClient IS NULL
         OR cc.in_flight_count < :maxPerClient
         OR cc.in_flight_count IS NULL
         OR t.priority <= 0
      )
    ORDER BY t.created_at ASC, t.id ASC
    LIMIT :limit
    FOR UPDATE OF t SKIP LOCKED
    """, nativeQuery = true)
    List<TaskEntity> findAndLockOldestDispatchable(
            @Param("limit") int limit,
            @Param("maxPerClient") Integer maxPerClient
    );

    @Query(value = """
        SELECT t.fairness_key,
               COUNT(*),
               SUM(CASE WHEN t.priority <= 0 THEN 1 ELSE 0 END),
               MAX(t.weight),
               COALESCE(MAX(cc.in_flight_count), 0)
        FROM tasks t
        LEFT JOIN client_counts cc ON cc.fairness_key = t.fairness_key
        WHERE t.status = 'QUEUED'
          AND t.is_sequential = false
        GROUP BY t.fairness_key
        """, nativeQuery = true)
    List<Object[]> findQueuedLeaves();

    /** {@code limits} is a JSON array of {"key": ..., "limit": ...}; JSON avoids driver-specific array binding. */
    @Query(value = """
        SELECT q.*
        FROM ROWS FROM (jsonb_to_recordset(CAST(:limits AS jsonb)) AS (key text, "limit" int))
             WITH ORDINALITY AS wanted(key, "limit", position)
        CROSS JOIN LATERAL (
            SELECT t.*
            FROM tasks t
            WHERE t.fairness_key = wanted.key
              AND t.status = 'QUEUED'
              AND t.is_sequential = false
            ORDER BY t.priority ASC NULLS LAST, t.created_at ASC, t.id ASC
            LIMIT wanted."limit"
            FOR UPDATE OF t SKIP LOCKED
        ) q
        ORDER BY wanted.position, q.priority ASC NULLS LAST, q.created_at ASC, q.id ASC
        """, nativeQuery = true)
    List<TaskEntity> findAndLockQueuedHeads(@Param("limits") String limitsJson);

    @Query(value = """
        SELECT * FROM tasks
        WHERE status = 'QUEUED'
          AND is_sequential = false
          AND created_at < now() - (:olderThanMs || ' milliseconds')::interval
        ORDER BY created_at ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<TaskEntity> findStarvedTasks(@Param("olderThanMs") long olderThanMs, @Param("limit") int limit);

    Optional<TaskEntity> findByFairnessKeyAndSequenceNumberAndStatus(
        String fairnessKey, Long sequenceNumber, TaskStatus status);

    // Status is bound as a parameter: an enum literal in JPQL renders as '...'::TaskStatus, which is not the
    // PostgreSQL type name (task_status).
    @Query("""
        SELECT t FROM TaskEntity t
        WHERE t.requiresPreviousResult = true
          AND t.previousResult IS NULL
          AND t.status = :status
          AND t.dependsOnTaskId IS NOT NULL
        """)
    List<TaskEntity> findTasksWaitingForPreviousResult(@Param("status") TaskStatus status);

    // updated_at is DB-assigned by trg_set_updated_at; no need to set it here.
    @Modifying
    @Query("UPDATE TaskEntity t SET t.status = :newStatus WHERE t.id IN :ids")
    int updateStatusBatch(@Param("ids") List<UUID> ids, @Param("newStatus") TaskStatus newStatus);

    // Write-path optimizations (O1/O2): targeted UPDATEs replace load-modify-save
    // merge round-trips (each merge costs a SELECT plus a full-row UPDATE). Version is
    // bumped inline so concurrent holders still fail fast on stale state.

    /** Bulk dispatch transition for rows locked by {@link #findAndLockDispatchable}. */
    @Modifying
    @Query(value = """
        UPDATE tasks SET status = 'DISPATCHED', version = version + 1
        WHERE id IN :ids AND status = 'QUEUED'
        """, nativeQuery = true)
    int bulkMarkDispatched(@Param("ids") List<UUID> ids);

    /** Single dispatch transition, attaching the predecessor result for sequential tasks. */
    @Modifying
    @Query(value = """
        UPDATE tasks SET status = 'DISPATCHED', previous_result = :previousResult,
            version = version + 1
        WHERE id = :id AND status = 'QUEUED'
        """, nativeQuery = true)
    int markDispatched(@Param("id") UUID id, @Param("previousResult") byte[] previousResult);

    /** Queueing transition for the priority calculator (status, priority, finish tag). */
    @Modifying
    @Query(value = """
        UPDATE tasks SET status = 'QUEUED', priority = :priority, virtual_finish = :virtualFinish,
            version = version + 1
        WHERE id = :id AND status = 'RECEIVED'
        """, nativeQuery = true)
    int markQueued(@Param("id") UUID id, @Param("priority") long priority,
        @Param("virtualFinish") Double virtualFinish);

    /** Atomic terminal transition: status guard and version check run in the UPDATE. */
    @Modifying
    @Query(value = """
        UPDATE tasks SET status = CAST(:status AS task_status), result = :result,
            last_error = :lastError, completed_at = :completedAt, version = version + 1
        WHERE id = :id AND status IN ('DISPATCHED', 'COMMITTED') AND version = :version
        """, nativeQuery = true)
    int completeTask(@Param("id") UUID id, @Param("version") long version,
        @Param("status") String status, @Param("result") byte[] result,
        @Param("lastError") String lastError, @Param("completedAt") Instant completedAt);

    /** Executor-ack transition; silently skips tasks that already moved on. */
    @Modifying
    @Query(value = """
        UPDATE tasks SET status = 'COMMITTED', version = version + 1
        WHERE id = :id AND status = 'DISPATCHED'
        """, nativeQuery = true)
    int markCommitted(@Param("id") UUID id);

    /** Timeout transition with the same in-UPDATE guards as {@link #completeTask}. */
    @Modifying
    @Query(value = """
        UPDATE tasks SET status = 'TIMEOUT', last_error = :lastError, completed_at = :completedAt,
            version = version + 1
        WHERE id = :id AND status IN ('DISPATCHED', 'COMMITTED') AND version = :version
        """, nativeQuery = true)
    int markTimeout(@Param("id") UUID id, @Param("version") long version,
        @Param("lastError") String lastError, @Param("completedAt") Instant completedAt);

    /** Bulk starvation promotion (priority → 0); rows already promoted are untouched. */
    @Modifying
    @Query(value = """
        UPDATE tasks SET priority = 0, version = version + 1
        WHERE id IN (
            SELECT id FROM tasks
            WHERE status = 'QUEUED'
              AND is_sequential = false
              AND (priority IS NULL OR priority <> 0)
              AND created_at < now() - (:olderThanMs || ' milliseconds')::interval
            ORDER BY created_at ASC
            LIMIT :limit
        )
        """, nativeQuery = true)
    int bulkPromoteStarvedTasks(@Param("olderThanMs") long olderThanMs, @Param("limit") int limit);

    @Query("""
        SELECT t.fairnessKey, COUNT(t)
        FROM TaskEntity t
        WHERE t.status IN :statuses
        GROUP BY t.fairnessKey
        """)
    List<Object[]> countByStatusesGroupByFairnessKey(@Param("statuses") List<TaskStatus> statuses);

    @Query(value = """
        SELECT * FROM tasks
        WHERE status IN ('DISPATCHED', 'COMMITTED')
          AND updated_at < now() - (:olderThanMs || ' milliseconds')::interval
        ORDER BY updated_at ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<TaskEntity> findTimedOutInFlight(@Param("olderThanMs") long olderThanMs, @Param("limit") int limit);
}
