package org.synanton.equalix.domain.port.out;

/** Outgoing port for recording task execution metrics used by the AdaptiveRpsController. */
public interface PerformanceMonitorPort {

    /**
     * Records the outcome of a completed task.
     *
     * @param fairnessKey client/tenant identifier for per-client metric breakdown
     * @param durationMs time from DISPATCHED to completion in milliseconds
     * @param success whether the task succeeded
     */
    void recordCompletion(String fairnessKey, long durationMs, boolean success);
}
