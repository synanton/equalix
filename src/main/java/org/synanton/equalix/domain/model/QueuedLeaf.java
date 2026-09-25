package org.synanton.equalix.domain.model;

/**
 * Dispatchable backlog of one fairness key, as seen by the hierarchical dispatcher.
 *
 * @param fairnessKey leaf key
 * @param queued QUEUED non-sequential tasks
 * @param promoted of those, tasks promoted by the starvation deadline (priority 0)
 * @param maxWeight largest task weight among them; the leaf's weight unless overridden
 * @param inFlight authoritative in-flight count from {@code client_counts}, for the hard quota
 */
public record QueuedLeaf(String fairnessKey, int queued, int promoted, double maxWeight, int inFlight) {
}
