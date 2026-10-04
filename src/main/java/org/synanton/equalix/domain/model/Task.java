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
    /** Weighted virtual finish tag assigned when the task is queued; null for tasks queued before EQX-3. */
    @Nullable
    private Double virtualFinish;
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
    /** Optimistic-lock version; must round-trip through the domain model so repeated saves do not conflict. */
    private long version;

    // Sequential execution fields
    @Nullable
    private Long sequenceNumber;
    @Nullable
    private UUID dependsOnTaskId;
    private boolean isSequential;
    @Nullable
    private byte[] previousResult;
    private boolean requiresPreviousResult;

    /** Returns the scheduling weight, treating a missing or non-positive weight as 1.0. */
    public double effectiveWeight() {
        return weight == null || weight.signum() <= 0 ? 1.0 : weight.doubleValue();
    }
}
