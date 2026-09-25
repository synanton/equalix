package org.synanton.equalix.domain.port.out;

/** Outgoing port for scheduler metrics: task execution (also fed to the AdaptiveRpsController) and CMS accuracy. */
public interface PerformanceMonitorPort {

    /**
     * Records the outcome of a completed task.
     *
     * @param fairnessKey client/tenant identifier for per-client metric breakdown
     * @param durationMs time from DISPATCHED to completion in milliseconds
     * @param success whether the task succeeded
     */
    void recordCompletion(String fairnessKey, long durationMs, boolean success);

    /**
     * Records one sample of the CMS estimation error {@code e_k = F̂_k - F_k} for a fairness key.
     *
     * @param error signed error; positive is an overestimate
     */
    void recordCmsEstimationError(long error);
}
