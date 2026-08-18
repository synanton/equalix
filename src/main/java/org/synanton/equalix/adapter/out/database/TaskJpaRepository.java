package org.synanton.equalix.adapter.out.database;

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
      )
    ORDER BY t.priority ASC NULLS LAST
    LIMIT :limit
    FOR UPDATE OF t SKIP LOCKED
    """, nativeQuery = true)
    List<TaskEntity> findAndLockDispatchable(
            @Param("limit") int limit,
            @Param("maxPerClient") Integer maxPerClient
    );

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

    @Query("""
        SELECT t FROM TaskEntity t
        WHERE t.requiresPreviousResult = true
          AND t.previousResult IS NULL
          AND t.status = org.synanton.equalix.domain.model.TaskStatus.QUEUED
          AND t.dependsOnTaskId IS NOT NULL
        """)
    List<TaskEntity> findTasksWaitingForPreviousResult();

    @Modifying
    @Query("UPDATE TaskEntity t SET t.status = :newStatus, t.updatedAt = CURRENT_TIMESTAMP WHERE t.id IN :ids")
    int updateStatusBatch(@Param("ids") List<UUID> ids, @Param("newStatus") TaskStatus newStatus);

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
