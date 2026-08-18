package org.synanton.equalix.domain.port.out;

import java.util.Map;

/**
 * Outgoing port for the in-memory Count-Min Sketch used to approximate per-client in-flight counts.
 * Estimates are inherently approximate (error bound ~1% with default config) - never use them for
 * hard quota enforcement; use ClientCountsRepositoryPort for that.
 */
public interface CMSProviderPort {

    /**
     * Adjusts the approximate count for the given key by delta.
     * Use +1 on dispatch, -1 on completion. Negative deltas may produce cells below zero
     * due to CMS over-counting; estimateCount always returns max(0, estimate).
     */
    void add(String key, long delta);

    /** Returns max(0, minimum across hash-row estimates) for the given key. */
    long estimateCount(String key);

    /**
     * Replaces the sketch contents with the provided snapshot.
     * Called by the watchdog after reconciling client_counts with actual DB state.
     */
    void rebuild(Map<String, Integer> snapshot);

    /** Returns the total approximate in-flight count across all keys. */
    long totalInFlight();
}
