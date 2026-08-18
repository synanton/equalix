package org.synanton.equalix.domain.model;

import java.time.Instant;
import lombok.Data;
import lombok.experimental.Accessors;

/** Durable per-client in-flight task count used for hard quota enforcement and watchdog reconciliation. */
@Data
@Accessors(chain = true)
public class ClientCounts {

    private String fairnessKey;
    private int inFlightCount;
    private Instant updatedAt;
}
