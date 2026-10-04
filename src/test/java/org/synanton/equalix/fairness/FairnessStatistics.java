package org.synanton.equalix.fairness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fairness error measures from mathematical invariants §5-§6 over an ordered sequence of dispatched fairness keys.
 *
 * <ul>
 *   <li>Expected share: {@code E_k = w_k / Σ w_j}</li>
 *   <li>Observed share over window W: {@code S_k(W) = D_k(W) / |W|}</li>
 *   <li>Fairness error: {@code ε_k(W) = |S_k(W) - E_k|}, and {@code ε_max(W) = max_k ε_k(W)}</li>
 * </ul>
 */
public final class FairnessStatistics {

    private final Map<String, Double> expectedShares;

    public FairnessStatistics(Map<String, BigDecimal> weights) {
        double totalWeight = weights.values().stream().mapToDouble(BigDecimal::doubleValue).sum();
        Map<String, Double> shares = new LinkedHashMap<>();
        weights.forEach((fairnessKey, weight) -> shares.put(fairnessKey, weight.doubleValue() / totalWeight));
        this.expectedShares = shares;
    }

    public Map<String, Double> expectedShares() {
        return expectedShares;
    }

    /** Observed shares over the first {@code window} dispatches. */
    public Map<String, Double> observedShares(List<String> dispatchSequence, int window) {
        Map<String, Integer> counts = new HashMap<>();
        for (String fairnessKey : dispatchSequence.subList(0, window)) {
            counts.merge(fairnessKey, 1, Integer::sum);
        }
        Map<String, Double> shares = new LinkedHashMap<>();
        expectedShares.keySet().forEach(fairnessKey ->
            shares.put(fairnessKey, (double) counts.getOrDefault(fairnessKey, 0) / window));
        return shares;
    }

    /** {@code ε_max} over the first {@code window} dispatches. */
    public double prefixMaxError(List<String> dispatchSequence, int window) {
        Map<String, Double> observed = observedShares(dispatchSequence, window);
        return expectedShares.entrySet().stream()
            .mapToDouble(entry -> Math.abs(observed.get(entry.getKey()) - entry.getValue()))
            .max()
            .orElse(0.0);
    }

    /** Worst {@code ε_max} over every contiguous window of {@code window} dispatches. */
    public double slidingMaxError(List<String> dispatchSequence, int window) {
        Map<String, Integer> counts = new HashMap<>();
        double worstError = 0.0;
        for (int position = 0; position < dispatchSequence.size(); position++) {
            counts.merge(dispatchSequence.get(position), 1, Integer::sum);
            if (position >= window) {
                counts.merge(dispatchSequence.get(position - window), -1, Integer::sum);
            }
            if (position >= window - 1) {
                worstError = Math.max(worstError, maxError(counts, window));
            }
        }
        return worstError;
    }

    private double maxError(Map<String, Integer> counts, int window) {
        return expectedShares.entrySet().stream()
            .mapToDouble(entry ->
                Math.abs((double) counts.getOrDefault(entry.getKey(), 0) / window - entry.getValue()))
            .max()
            .orElse(0.0);
    }
}
