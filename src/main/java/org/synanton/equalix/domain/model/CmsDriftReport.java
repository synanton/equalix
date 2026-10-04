package org.synanton.equalix.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * CMS estimation drift {@code drift_k = F̂_k - F_k} measured by the watchdog just before it rebuilds the sketch
 * (invariants §16, EQX-5).
 *
 * @param measuredAt when the drift was measured
 * @param keysSampled number of keys compared with the task table
 * @param keysDrifting number of sampled keys with non-zero drift
 * @param maxDrift largest overestimate, or 0 when no key is overestimated
 * @param minDrift largest underestimate as a negative number, or 0 when no key is underestimated
 * @param absoluteDriftTotal sum of {@code |drift_k|} over all sampled keys
 * @param topDrifting keys with non-zero drift ordered by {@code |drift_k|} descending then key, capped at the
 *     configured size
 * @param layers layer name of every key in {@code topDrifting} (EQX-7; {@code key} in flat mode)
 * @param absoluteDriftByLayer sum of {@code |drift_k|} per layer over all sampled keys
 */
public record CmsDriftReport(
    Instant measuredAt,
    int keysSampled,
    int keysDrifting,
    long maxDrift,
    long minDrift,
    long absoluteDriftTotal,
    Map<String, Long> topDrifting,
    Map<String, String> layers,
    Map<String, Long> absoluteDriftByLayer) {

    /** Layer label used when no hierarchy is configured. */
    public static final String FLAT_LAYER = "key";

    private static final Comparator<Map.Entry<String, Long>> LARGEST_DRIFT_FIRST =
        Comparator.<Map.Entry<String, Long>>comparingLong(entry -> Math.abs(entry.getValue()))
            .reversed()
            .thenComparing(Map.Entry::getKey);

    public CmsDriftReport {
        topDrifting = Collections.unmodifiableMap(new LinkedHashMap<>(topDrifting));
        layers = Map.copyOf(layers);
        absoluteDriftByLayer = Map.copyOf(absoluteDriftByLayer);
    }

    /**
     * Summarises per-key drift.
     *
     * @param driftByKey drift of every sampled key, including keys with zero drift
     * @param maxKeys cap on {@link #topDrifting()}
     */
    public static CmsDriftReport of(Map<String, Long> driftByKey, int maxKeys, Instant measuredAt) {
        return of(driftByKey, maxKeys, measuredAt, key -> FLAT_LAYER);
    }

    /**
     * Summarises per-key drift with a layer per key.
     *
     * @param layerOf layer name of a sampled key
     */
    public static CmsDriftReport of(Map<String, Long> driftByKey, int maxKeys, Instant measuredAt,
        Function<String, String> layerOf) {
        long maxDrift = 0;
        long minDrift = 0;
        long absoluteDriftTotal = 0;
        int keysDrifting = 0;
        Map<String, Long> absoluteDriftByLayer = new TreeMap<>();
        for (Map.Entry<String, Long> entry : driftByKey.entrySet()) {
            long drift = entry.getValue();
            maxDrift = Math.max(maxDrift, drift);
            minDrift = Math.min(minDrift, drift);
            absoluteDriftTotal += Math.abs(drift);
            keysDrifting += drift != 0 ? 1 : 0;
            absoluteDriftByLayer.merge(layerOf.apply(entry.getKey()), Math.abs(drift), Long::sum);
        }

        Map<String, Long> topDrifting = new LinkedHashMap<>();
        driftByKey.entrySet().stream()
            .filter(entry -> entry.getValue() != 0)
            .sorted(LARGEST_DRIFT_FIRST)
            .limit(Math.max(0, maxKeys))
            .forEach(entry -> topDrifting.put(entry.getKey(), entry.getValue()));

        Map<String, String> layers = new HashMap<>();
        topDrifting.keySet().forEach(key -> layers.put(key, layerOf.apply(key)));
        return new CmsDriftReport(measuredAt, driftByKey.size(), keysDrifting, maxDrift, minDrift,
            absoluteDriftTotal, topDrifting, layers, absoluteDriftByLayer);
    }
}
