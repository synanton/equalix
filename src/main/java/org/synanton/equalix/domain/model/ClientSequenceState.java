package org.synanton.equalix.domain.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.experimental.Accessors;
import org.jspecify.annotations.Nullable;

/** Tracks sequential execution progress for a given fairness key. */
@Data
@Accessors(chain = true)
public class ClientSequenceState {

    private String fairnessKey;
    private long lastCompletedSequence;
    private long lastDispatchedSequence;
    @Nullable private UUID currentExecutingTaskId;
    private boolean isBlocked;
    @Nullable private Instant blockedAt;
    private Instant updatedAt;
}
