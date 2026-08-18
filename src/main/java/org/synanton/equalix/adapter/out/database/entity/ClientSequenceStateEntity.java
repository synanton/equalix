package org.synanton.equalix.adapter.out.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.experimental.Accessors;
import org.jspecify.annotations.Nullable;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "client_sequence_state")
public class ClientSequenceStateEntity {

    @Id
    @Column(name = "fairness_key")
    private String fairnessKey;

    @Column(name = "last_completed_sequence", nullable = false)
    private long lastCompletedSequence;

    @Column(name = "last_dispatched_sequence", nullable = false)
    private long lastDispatchedSequence;

    @Nullable
    @Column(name = "current_executing_task_id")
    private UUID currentExecutingTaskId;

    @Column(name = "is_blocked", nullable = false)
    private boolean isBlocked;

    @Nullable
    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
