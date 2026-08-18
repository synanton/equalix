package org.synanton.equalix.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;
import org.jspecify.annotations.Nullable;

/**
 * Core domain entity representing a schedulable unit of work.
 */
@Data
@Accessors(chain = true)
public class Task {

    private UUID id;
    private String fairnessKey;
    private BigDecimal weight;
    private TaskStatus status;
    @Nullable
    private Long priority;
    private byte[] payload;
    private Instant createdAt;
    private Instant updatedAt;
    @Nullable
    private Instant completedAt;
    private int retryCount;
    @Nullable
    private String lastError;
    @Nullable
    private byte[] result;

    // Sequential execution fields
    @Nullable
    private Long sequenceNumber;
    @Nullable
    private UUID dependsOnTaskId;
    private boolean isSequential;
    @Nullable
    private byte[] previousResult;
    private boolean requiresPreviousResult;
}
