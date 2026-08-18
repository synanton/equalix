package org.synanton.equalix.adapter.out.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.TaskStatus;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    private UUID id;

    @Column(name = "fairness_key", nullable = false)
    private String fairnessKey;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal weight;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", columnDefinition = "task_status", nullable = false)
    private TaskStatus status;

    @Nullable
    private Long priority;

    @Column(columnDefinition = "BYTEA", nullable = false)
    private byte[] payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Nullable
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Nullable
    @Column(name = "last_error")
    private String lastError;

    @Nullable
    @Column(columnDefinition = "BYTEA")
    private byte[] result;

    @Version
    private long version;

    // Sequential execution columns
    @Nullable
    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Nullable
    @Column(name = "depends_on_task_id")
    private UUID dependsOnTaskId;

    @Column(name = "is_sequential")
    private boolean sequential;

    @Nullable
    @Column(name = "previous_result", columnDefinition = "BYTEA")
    private byte[] previousResult;

    @Column(name = "requires_previous_result", nullable = false)
    private boolean requiresPreviousResult;
}
