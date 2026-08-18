package org.synanton.equalix.adapter.out.database;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.synanton.equalix.adapter.out.database.entity.ClientSequenceStateEntity;

public interface ClientSequenceStateJpaRepository extends JpaRepository<ClientSequenceStateEntity, String> {

    List<ClientSequenceStateEntity> findByIsBlockedTrue();

    /** Finds clients where no task is currently executing and not blocked, and a QUEUED sequential task exists. */
    @Query(value = """
        SELECT css.* FROM client_sequence_state css
        WHERE css.is_blocked = false
          AND css.current_executing_task_id IS NULL
          AND EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.fairness_key = css.fairness_key
              AND t.status = 'QUEUED'
              AND t.is_sequential = true
          )
        """, nativeQuery = true)
    List<ClientSequenceStateEntity> findReadyClients();
}
