package org.synanton.equalix.domain.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Empirical distribution of the CMS estimation error {@code e_k = F̂_k - F_k} (mathematical invariants §16, §20).
 *
 * <p>Errors are integers, so samples are kept as an exact histogram: percentiles are exact and memory grows with
 * the number of distinct error values, not with the number of samples. Not thread-safe.
 */
public class CmsErrorStatistics {

    private final TreeMap<Long, Long> histogram = new TreeMap<>();
    private long count;
    private long sum;
    private long absoluteSum;
    private long overestimations;
    private long underestimations;

    /** Records one sample of {@code e_k}. */
    public void record(long error) {
        record(error, 1L);
    }

    /** Records {@code occurrences} samples with the same error value. */
    public void record(long error, long occurrences) {
        if (occurrences <= 0) {
            return;
        }
        histogram.merge(error, occurrences, Long::sum);
        count += occurrences;
        sum += error * occurrences;
        absoluteSum += Math.abs(error) * occurrences;
        if (error > 0) {
            overestimations += occurrences;
        } else if (error < 0) {
            underestimations += occurrences;
        }
    }

    /** Adds all samples of {@code other} to this distribution. */
    public void merge(CmsErrorStatistics other) {
        other.histogram.forEach(this::record);
    }

    public long count() {
        return count;
    }

    /** Signed mean error; positive means the sketch overestimates on average. */
    public double mean() {
        return count == 0 ? 0.0 : (double) sum / count;
    }

    public double meanAbsolute() {
        return count == 0 ? 0.0 : (double) absoluteSum / count;
    }

    /** Largest signed error (worst overestimation), or 0 without samples. */
    public long max() {
        return count == 0 ? 0L : histogram.lastKey();
    }

    /** Smallest signed error (worst underestimation), or 0 without samples. */
    public long min() {
        return count == 0 ? 0L : histogram.firstKey();
    }

    public long maxAbsolute() {
        return Math.max(Math.abs(max()), Math.abs(min()));
    }

    /**
     * Nearest-rank percentile of {@code |e_k|}.
     *
     * @param quantile in {@code (0, 1]}, e.g. 0.99
     */
    public long percentileAbsolute(double quantile) {
        if (count == 0) {
            return 0L;
        }
        TreeMap<Long, Long> absolute = new TreeMap<>();
        histogram.forEach((error, occurrences) -> absolute.merge(Math.abs(error), occurrences, Long::sum));
        long rank = (long) Math.ceil(quantile * count);
        long seen = 0;
        for (Map.Entry<Long, Long> entry : absolute.entrySet()) {
            seen += entry.getValue();
            if (seen >= rank) {
                return entry.getKey();
            }
        }
        return absolute.lastKey();
    }

    public double overestimationFrequency() {
        return count == 0 ? 0.0 : (double) overestimations / count;
    }

    public double underestimationFrequency() {
        return count == 0 ? 0.0 : (double) underestimations / count;
    }

    public double exactFrequency() {
        return count == 0 ? 0.0 : (double) (count - overestimations - underestimations) / count;
    }

    /** Signed error value to number of samples, ascending by error. */
    public Map<Long, Long> histogram() {
        return Collections.unmodifiableMap(histogram);
    }

    @Override
    public String toString() {
        return String.format(
            "samples=%d mean=%.4f mean|e|=%.4f min=%d max=%d p95|e|=%d p99|e|=%d over=%.4f under=%.4f",
            count, mean(), meanAbsolute(), min(), max(), percentileAbsolute(0.95), percentileAbsolute(0.99),
            overestimationFrequency(), underestimationFrequency());
    }
}
