package org.synanton.equalix.adapter.out.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.SplittableRandom;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.CmsErrorStatistics;

/**
 * EQX-2: empirical distribution of the signed-update CMS error {@code e_k = F̂_k - F_k} (invariants §16, §20)
 * during one watchdog window, before reconciliation resets the sketch.
 *
 * <p>A seeded stream of +1 (dispatch) and -1 (completion) updates keeps about {@value #TARGET_IN_FLIGHT} tasks in
 * flight across Zipf-distributed keys, which approximates a few heavy tenants and a long tail. The window is
 * {@value #WINDOW_EVENTS} updates (5 minutes at 1,000 updates/s). {@value #SAMPLES_PER_WINDOW} times per window,
 * the error of every tracked key is sampled, where tracked means dispatched at least once (the recorder samples
 * {@code client_counts} rows the same way).
 *
 * <p>Anomaly scenarios model accounting faults that exist in the code today: {@code cms.add} runs inside the
 * dispatch/completion transaction, so a rollback leaves a phantom +1 (dispatch rolled back) or an extra -1
 * (completion rolled back and retried).
 *
 * <p>CSV output for plotting goes to {@code target/eqx-2/}.
 */
@Slf4j
class CmsSignedUpdateErrorExperimentTest {

    private static final int TARGET_IN_FLIGHT = 5_000;
    private static final int WINDOW_EVENTS = 300_000;
    private static final int SAMPLES_PER_WINDOW = 60;
    private static final double ZIPF_EXPONENT = 1.1;
    private static final long SEED = 20260925L;
    private static final Path OUTPUT = Path.of("target", "eqx-2");

    @Test
    void shouldNeverUnderestimateWithExactAccounting() {
        // Strict turnstile: every -1 matches an earlier +1 of the same key, so all true counts stay >= 0 and every
        // CMS cell is at least the key's own count.
        for (SketchSize size : SketchSize.values()) {
            for (int keys : new int[]{1_000, 10_000, 50_000}) {
                Run run = new Run(size, keys, 0.0, 0.0).execute();

                assertThat(run.statistics().min()).as("%s keys=%d min e_k", size, keys).isGreaterThanOrEqualTo(0);
                assertThat(run.statistics().underestimationFrequency()).isZero();
            }
        }
    }

    @Test
    void shouldStayWithinClassicalBoundOnCurrentInFlight() {
        // Per row, P[e_k > 2N/w] <= 1/2 (Markov); the minimum of d independent rows exceeds it with P <= 2^-d, where
        // N is the current total in flight rather than the total number of updates.
        for (SketchSize size : SketchSize.values()) {
            for (int keys : new int[]{1_000, 10_000, 50_000}) {
                Run run = new Run(size, keys, 0.0, 0.0).execute();

                assertThat(run.fractionAboveClassicalBound())
                    .as("%s keys=%d fraction of samples with e_k > 2N/w", size, keys)
                    .isLessThanOrEqualTo(Math.pow(0.5, size.depth()));
            }
        }
    }

    @Test
    void shouldDriftBothWaysWhenAccountingFaultsAccumulate() {
        Run run = new Run(SketchSize.PRODUCTION, 10_000, 0.001, 0.001).execute();
        List<Checkpoint> checkpoints = run.checkpoints();

        assertThat(run.statistics().underestimationFrequency()).isGreaterThan(0.0);
        assertThat(run.statistics().max()).isGreaterThan(0);
        // Faults are never reversed inside the window, so the worst error only grows until the watchdog rebuild.
        assertThat(checkpoints.getLast().statistics().maxAbsolute())
            .isGreaterThan(checkpoints.get(checkpoints.size() / 10).statistics().maxAbsolute());
    }

    @Test
    void shouldReturnToExactAccountingAfterWatchdogRebuild() {
        Run run = new Run(SketchSize.PRODUCTION, 10_000, 0.001, 0.001).execute();

        CmsErrorStatistics afterRebuild = run.rebuildAndSample();

        assertThat(afterRebuild.min()).isGreaterThanOrEqualTo(0);
        assertThat(afterRebuild.maxAbsolute()).isLessThanOrEqualTo(run.statistics().maxAbsolute());
    }

    @Test
    void shouldCollideInEveryRowForKeysWithEqualStringHashCode() {
        // Known limitation, measured here and fixed separately: every row is derived from the 32-bit
        // String.hashCode(), so keys with equal hash codes share all d cells and even the production-size sketch
        // cannot separate them. Random keys collide with probability about K^2 / 2^33, but structured ids such as
        // "...Aa" and "...BB" collide deterministically.
        QueueProperties props = new QueueProperties();
        props.getCms().setWidth(SketchSize.PRODUCTION.width());
        props.getCms().setDepth(SketchSize.PRODUCTION.depth());
        CountMinSketchAdapter sketch = new CountMinSketchAdapter(props);
        assertThat("tenant-Aa".hashCode()).isEqualTo("tenant-BB".hashCode());

        sketch.add("tenant-BB", 40);

        assertThat(sketch.estimateCount("tenant-Aa")).isEqualTo(40);
    }

    @Test
    void shouldWriteDistributionReport() {
        List<String> summary = new ArrayList<>();
        summary.add("scenario,width,depth,keys,phantom_rate,double_decrement_rate,samples,mean,mean_abs,min,max,"
            + "p95_abs,p99_abs,over_freq,under_freq,classical_bound,fraction_above_bound");
        List<Run> runs = new ArrayList<>();
        for (SketchSize size : SketchSize.values()) {
            for (int keys : new int[]{1_000, 10_000, 50_000}) {
                runs.add(new Run(size, keys, 0.0, 0.0).execute());
            }
        }
        runs.add(new Run(SketchSize.PRODUCTION, 10_000, 0.001, 0.001).execute());
        runs.add(new Run(SketchSize.TEST, 10_000, 0.001, 0.001).execute());

        for (Run run : runs) {
            CmsErrorStatistics statistics = run.statistics();
            log.info("EQX-2 {} -> {} bound(2N/w)={} above={}", run.name(), statistics,
                format(run.classicalBound()), format(run.fractionAboveClassicalBound()));
            summary.add(String.join(",", run.name(), String.valueOf(run.size().width()),
                String.valueOf(run.size().depth()), String.valueOf(run.keys()), format(run.phantomRate()),
                format(run.doubleDecrementRate()), String.valueOf(statistics.count()), format(statistics.mean()),
                format(statistics.meanAbsolute()), String.valueOf(statistics.min()), String.valueOf(statistics.max()),
                String.valueOf(statistics.percentileAbsolute(0.95)),
                String.valueOf(statistics.percentileAbsolute(0.99)),
                format(statistics.overestimationFrequency()), format(statistics.underestimationFrequency()),
                format(run.classicalBound()), format(run.fractionAboveClassicalBound())));
            writeHistogram(run);
            writeTimeline(run);
        }
        write(OUTPUT.resolve("summary.csv"), summary);

        assertThat(OUTPUT.resolve("summary.csv")).exists();
    }

    private static void writeHistogram(Run run) {
        List<String> lines = new ArrayList<>();
        lines.add("error,samples");
        run.statistics().histogram().forEach((error, samples) -> lines.add(error + "," + samples));
        write(OUTPUT.resolve("histogram-" + run.name() + ".csv"), lines);
    }

    private static void writeTimeline(Run run) {
        List<String> lines = new ArrayList<>();
        lines.add("event,in_flight,mean,min,max,p99_abs");
        for (Checkpoint checkpoint : run.checkpoints()) {
            CmsErrorStatistics statistics = checkpoint.statistics();
            lines.add(checkpoint.event() + "," + checkpoint.inFlight() + "," + format(statistics.mean()) + ","
                + statistics.min() + "," + statistics.max() + "," + statistics.percentileAbsolute(0.99));
        }
        write(OUTPUT.resolve("timeline-" + run.name() + ".csv"), lines);
    }

    private static void write(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    enum SketchSize {
        TEST(1_024, 3),
        PRODUCTION(65_536, 5);

        private final int width;
        private final int depth;

        SketchSize(int width, int depth) {
            this.width = width;
            this.depth = depth;
        }

        int width() {
            return width;
        }

        int depth() {
            return depth;
        }
    }

    private record Checkpoint(long event, int inFlight, CmsErrorStatistics statistics) {
    }

    /** One seeded window of updates against a fresh sketch, with exact counts kept alongside. */
    private static final class Run {

        private final SketchSize size;
        private final int keys;
        private final double phantomRate;
        private final double doubleDecrementRate;
        private final CountMinSketchAdapter sketch;
        private final String[] keyNames;
        private final double[] zipfCdf;
        private final long[] exact;
        private final boolean[] tracked;
        private final SplittableRandom random = new SplittableRandom(SEED);
        private final CmsErrorStatistics statistics = new CmsErrorStatistics();
        private final List<Checkpoint> checkpoints = new ArrayList<>();
        private int[] inFlightTasks = new int[TARGET_IN_FLIGHT * 2];
        private int inFlight;
        private long samplesAboveBound;
        private long boundSamples;
        private double boundSum;

        Run(SketchSize size, int keys, double phantomRate, double doubleDecrementRate) {
            this.size = size;
            this.keys = keys;
            this.phantomRate = phantomRate;
            this.doubleDecrementRate = doubleDecrementRate;
            QueueProperties props = new QueueProperties();
            props.getCms().setWidth(size.width());
            props.getCms().setDepth(size.depth());
            this.sketch = new CountMinSketchAdapter(props);
            this.keyNames = new String[keys];
            this.exact = new long[keys];
            this.tracked = new boolean[keys];
            this.zipfCdf = new double[keys];
            double total = 0.0;
            for (int rank = 0; rank < keys; rank++) {
                keyNames[rank] = "tenant-" + rank;
                total += 1.0 / Math.pow(rank + 1, ZIPF_EXPONENT);
                zipfCdf[rank] = total;
            }
            for (int rank = 0; rank < keys; rank++) {
                zipfCdf[rank] /= total;
            }
        }

        Run execute() {
            int sampleEvery = WINDOW_EVENTS / SAMPLES_PER_WINDOW;
            for (int event = 1; event <= WINDOW_EVENTS; event++) {
                boolean dispatch = inFlight == 0
                    || random.nextDouble() < (inFlight < TARGET_IN_FLIGHT ? 0.6 : 0.4);
                if (dispatch) {
                    dispatch();
                } else {
                    complete();
                }
                if (event % sampleEvery == 0) {
                    checkpoints.add(new Checkpoint(event, inFlight, sampleAll(true)));
                }
            }
            return this;
        }

        /** Rebuilds the sketch from exact counts, as the watchdog does, and samples once. */
        CmsErrorStatistics rebuildAndSample() {
            Map<String, Integer> snapshot = new HashMap<>();
            for (int rank = 0; rank < keys; rank++) {
                if (exact[rank] > 0) {
                    snapshot.put(keyNames[rank], (int) exact[rank]);
                }
            }
            sketch.rebuild(snapshot);
            return sampleAll(false);
        }

        private void dispatch() {
            int rank = zipfRank();
            tracked[rank] = true;
            sketch.add(keyNames[rank], 1);
            if (random.nextDouble() < phantomRate) {
                return; // transaction rolled back after cms.add: no task is actually in flight
            }
            exact[rank]++;
            if (inFlight == inFlightTasks.length) {
                inFlightTasks = Arrays.copyOf(inFlightTasks, inFlightTasks.length * 2);
            }
            inFlightTasks[inFlight++] = rank;
        }

        private void complete() {
            int index = random.nextInt(inFlight);
            int rank = inFlightTasks[index];
            inFlightTasks[index] = inFlightTasks[--inFlight];
            sketch.add(keyNames[rank], -1);
            if (random.nextDouble() < doubleDecrementRate) {
                sketch.add(keyNames[rank], -1); // completion rolled back after cms.add, then retried
            }
            exact[rank]--;
        }

        private CmsErrorStatistics sampleAll(boolean accumulate) {
            CmsErrorStatistics sample = new CmsErrorStatistics();
            double bound = 2.0 * inFlight / size.width();
            for (int rank = 0; rank < keys; rank++) {
                if (!tracked[rank]) {
                    continue;
                }
                long error = sketch.estimateCount(keyNames[rank]) - exact[rank];
                sample.record(error);
                if (accumulate) {
                    boundSamples++;
                    boundSum += bound;
                    if (error > bound) {
                        samplesAboveBound++;
                    }
                }
            }
            if (accumulate) {
                statistics.merge(sample);
            }
            return sample;
        }

        private int zipfRank() {
            int index = Arrays.binarySearch(zipfCdf, random.nextDouble());
            return Math.min(keys - 1, index >= 0 ? index : -index - 1);
        }

        String name() {
            String faults = phantomRate > 0 || doubleDecrementRate > 0 ? "-faults" : "";
            return size.name().toLowerCase(Locale.ROOT) + "-" + keys + faults;
        }

        SketchSize size() {
            return size;
        }

        int keys() {
            return keys;
        }

        double phantomRate() {
            return phantomRate;
        }

        double doubleDecrementRate() {
            return doubleDecrementRate;
        }

        CmsErrorStatistics statistics() {
            return statistics;
        }

        List<Checkpoint> checkpoints() {
            return checkpoints;
        }

        /** Mean of the classical bound 2N/w over all samples. */
        double classicalBound() {
            return boundSamples == 0 ? 0.0 : boundSum / boundSamples;
        }

        double fractionAboveClassicalBound() {
            return boundSamples == 0 ? 0.0 : (double) samplesAboveBound / boundSamples;
        }
    }
}
