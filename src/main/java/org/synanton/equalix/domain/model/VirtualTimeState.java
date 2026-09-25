package org.synanton.equalix.domain.model;

import java.time.Instant;
import lombok.Data;
import lombok.experimental.Accessors;

/** Durable weighted virtual-time state of one fairness key. */
@Data
@Accessors(chain = true)
public class VirtualTimeState {

    private String fairnessKey;

    /** T_k: accumulated service position of the key, advanced on every dispatch. */
    private double virtualTime;

    /** Finish tag of the most recently queued task; T_k plus the virtual cost of tasks still waiting. */
    private double virtualFinish;

    private Instant updatedAt;
}
