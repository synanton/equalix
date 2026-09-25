package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.AdaptiveRpsProperties;

/**
 * EQX-6: closed-loop simulation of {@link AdaptiveRpsController} against a modelled remote executor.
 *
 * <p>Executor model (one-second steps, per-completion samples): latency {@code base / (1 - ρ)} with
 * {@code ρ = rps / capacity}, base {@value #BASE_LATENCY_MS} ms, capped at {@value #MAX_LATENCY_MS} ms,
 * log-normal noise per sample, errors at {@value #BASE_ERROR_RATE} plus {@code ρ - 1} when overloaded. With
 * target 200 ms the ideal operating point is ρ = 0.5, i.e. half the capacity.
 *
 * <p>Workloads:
 * <ul>
 *   <li>Unless noted, capacity is 50/s (ideal 25 rps) and noise σ = 0.3.</li>
 *   <li><b>transient</b>: every 20 s the latency is ×4 for 2 s, independent of load. Throttling for these
 *       spikes is pure loss.</li>
 *   <li><b>long-spikes</b>: every 120 s the latency is ×4 for 10 s, independent of load.</li>
 *   <li><b>capacity-loss</b>: capacity halves for 5 minutes. The controller must follow it down (safety) and
 *       back up.</li>
 *   <li><b>low-rate-noisy</b>: a slow pool (capacity 6/s, about 3 samples per second) with heavy-tailed noise
 *       (σ = 0.8); the case smoothing is meant for.</li>
 * </ul>
 * The stock thresholds and factors are used throughout; configurations only change the three EQX-6
 * parameters. {@code stock} (alpha 1, interval 0, confirmations 1) is the pre-EQX-6 controller.
 */
@Slf4j
class AdaptiveRpsStabilitySimulationTest {

    private static final double BASE_LATENCY_MS = 100.0;
    private static final double MAX_LATENCY_MS = 5_000.0;
    private static final double TARGET_LATENCY_MS = 200.0;
    private static final double BASE_ERROR_RATE = 0.002;
    private static final int SIMULATED_SECONDS = 1_800;
    private static final int WARM_UP_SECONDS = 300;
    private static final long SEED = 6L;

    private static final String STOCK = "stock";
    private static final String INTERVAL = "interval";
    private static final String RECOMMENDED = "recommended";
    private static final List<String> PER_COMPLETION = List.of(STOCK, "stock+ema", "stock+dampener");

    private static final Map<String, Stability> CONFIGURATIONS = new LinkedHashMap<>();
    private static final Map<String, Workload> WORKLOADS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Result>> RESULTS = new LinkedHashMap<>();

    static {
        CONFIGURATIONS.put(STOCK, new Stability(1.0, 0, 1));
        CONFIGURATIONS.put("stock+ema", new Stability(0.7, 0, 1));
        CONFIGURATIONS.put("stock+dampener", new Stability(1.0, 0, 3));
        CONFIGURATIONS.put(INTERVAL, new Stability(1.0, 2_000, 1));
        CONFIGURATIONS.put("interval+ema", new Stability(0.7, 2_000, 1));
        CONFIGURATIONS.put("interval+dampener", new Stability(1.0, 2_000, 3));
        CONFIGURATIONS.put(RECOMMENDED, new Stability(0.7, 2_000, 3));

        WORKLOADS.put("transient", new Workload(50, 0.3, 20, 2, 4.0, 0, 0));
        WORKLOADS.put("long-spikes", new Workload(50, 0.3, 120, 10, 4.0, 0, 0));
        WORKLOADS.put("capacity-loss", new Workload(50, 0.3, 0, 0, 1.0, 900, 300));
        WORKLOADS.put("low-rate-noisy", new Workload(6, 0.8, 0, 0, 1.0, 0, 0));
    }

    @BeforeAll
    static void simulateAll() {
        WORKLOADS.forEach((workloadName, workload) -> {
            Map<String, Result> byConfiguration = new LinkedHashMap<>();
            CONFIGURATIONS.forEach((name, stability) -> {
                Result result = simulate(stability, workload);
                byConfiguration.put(name, result);
                log.info("EQX-6 {} {} {} -> {}", workloadName, name, stability, result);
            });
            RESULTS.put(workloadName, byConfiguration);
        });
    }

    @Test
    void shouldCollapseWhenAdjustingOnEveryCompletionEvenWithSmoothingOrDampener() {
        // One spike's evidence stays in the window for ~100 completions and is re-applied each time, so the rate
        // compounds down to min-rps; a count-based window then refreshes slowly at that rate.
        RESULTS.forEach((workload, results) -> PER_COMPLETION.forEach(configuration ->
            assertThat(results.get(configuration).overThrottledFraction())
                .as("%s / %s over-throttled", workload, configuration)
                .isGreaterThan(0.5)));
    }

    @Test
    void shouldNotOverThrottleWithRecommendedSettings() {
        RESULTS.forEach((workload, results) -> assertThat(results.get(RECOMMENDED).overThrottledFraction())
            .as("%s over-throttled", workload)
            .isLessThanOrEqualTo(0.05));
    }

    @Test
    void shouldStayWithinLatencyTargetWithRecommendedSettings() {
        RESULTS.forEach((workload, results) -> {
            Result recommended = results.get(RECOMMENDED);
            assertThat(recommended.overloadedFraction()).as("%s overloaded", workload).isLessThanOrEqualTo(0.01);
            assertThat(recommended.p99LoadLatencyMs()).as("%s p99 load latency", workload)
                .isLessThanOrEqualTo(1.5 * TARGET_LATENCY_MS);
        });
    }

    @Test
    void shouldDampenOscillationComparedWithIntervalAlone() {
        for (String workload : List.of("transient", "low-rate-noisy")) {
            Map<String, Result> results = RESULTS.get(workload);
            assertThat(results.get(RECOMMENDED).reversalsPerHour())
                .as("%s reversals per hour", workload)
                .isLessThanOrEqualTo(0.5 * results.get(INTERVAL).reversalsPerHour());
        }
    }

    private static Result simulate(Stability stability, Workload workload) {
        ManualClock clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
        AdaptiveRpsController controller = new AdaptiveRpsController(properties(stability, workload), clock);
        SplittableRandom random = new SplittableRandom(SEED);

        List<Double> rpsSeries = new ArrayList<>();
        List<Boolean> overloadSeries = new ArrayList<>();
        List<Double> latencySeries = new ArrayList<>();
        double carry = 0.0;
        for (int second = 0; second < SIMULATED_SECONDS; second++) {
            double rps = controller.getCurrentRps();
            double load = rps / workload.capacity(second);
            double loadLatency = Math.min(MAX_LATENCY_MS, BASE_LATENCY_MS / Math.max(0.02, 1 - load));
            double meanLatency = loadLatency * workload.spikeMultiplier(second);
            double errorRate = BASE_ERROR_RATE + Math.max(0.0, load - 1.0);

            carry += rps;
            int completions = (int) carry;
            carry -= completions;
            long stepMillis = completions == 0 ? 1_000 : 1_000 / completions;
            for (int completion = 0; completion < completions; completion++) {
                clock.advance(Duration.ofMillis(stepMillis));
                double sigma = workload.noiseSigma();
                double noise = Math.exp(sigma * gaussian(random) - sigma * sigma / 2);
                controller.recordCompletion(Math.round(meanLatency * noise), random.nextDouble() >= errorRate);
            }
            clock.advance(Duration.ofMillis(1_000 - stepMillis * completions));
            if (second >= WARM_UP_SECONDS) {
                rpsSeries.add(controller.getCurrentRps());
                // Load-induced latency only: spikes are external and not the controller's doing.
                overloadSeries.add(loadLatency > 2 * TARGET_LATENCY_MS);
                latencySeries.add(loadLatency);
            }
        }
        return Result.of(rpsSeries, overloadSeries, latencySeries, workload, controller);
    }

    private static AdaptiveRpsProperties properties(Stability stability, Workload workload) {
        AdaptiveRpsProperties props = new AdaptiveRpsProperties();
        props.setEnabled(true);
        props.setInitialRps(workload.idealRps());
        props.setMinRps(Math.min(1.0, workload.idealRps() / 10));
        props.setMaxRps(100);
        props.setTargetLatencyMs(200);
        props.setLatencyThreshold(0.2);
        props.setErrorThreshold(0.05);
        props.setWindowSize(100);
        props.setMinSamples(10);
        props.setEmergencyFactor(0.5);
        props.setDecreaseFactor(0.9);
        props.setIncreaseFactor(1.05);
        props.setIncreaseErrorThreshold(0.01);
        props.setLatencyEmaAlpha(stability.alpha());
        props.setAdjustmentIntervalMs(stability.intervalMs());
        props.setDirectionChangeConfirmations(stability.confirmations());
        return props;
    }

    private static double gaussian(SplittableRandom random) {
        // Box-Muller; SplittableRandom has no nextGaussian.
        double first = random.nextDouble();
        double second = random.nextDouble();
        return Math.sqrt(-2 * Math.log(Math.max(first, 1e-12))) * Math.cos(2 * Math.PI * second);
    }

    record Stability(double alpha, long intervalMs, int confirmations) {
    }

    /**
     * @param capacityRps sustainable rate; the ideal operating point is half of it
     * @param noiseSigma σ of the log-normal per-sample latency noise
     * @param spikePeriodS seconds between load-independent latency spikes; 0 for none
     * @param spikeDurationS spike length
     * @param spikeFactor latency multiplier during a spike
     * @param capacityLossStartS second at which capacity halves; 0 for never
     * @param capacityLossDurationS how long capacity stays halved
     */
    record Workload(double capacityRps, double noiseSigma, int spikePeriodS, int spikeDurationS,
                    double spikeFactor, int capacityLossStartS, int capacityLossDurationS) {

        double idealRps() {
            return capacityRps / 2;
        }


        double spikeMultiplier(int second) {
            boolean spiking = spikePeriodS > 0 && second >= spikePeriodS && second % spikePeriodS < spikeDurationS;
            return spiking ? spikeFactor : 1.0;
        }

        double capacity(int second) {
            boolean lost = capacityLossStartS > 0
                && second >= capacityLossStartS && second < capacityLossStartS + capacityLossDurationS;
            return lost ? capacityRps / 2 : capacityRps;
        }
    }

    /**
     * @param reversalsPerHour sign changes of the per-second RPS trend (oscillation)
     * @param meanRps mean allowed rate (throughput)
     * @param coefficientOfVariation standard deviation over mean of the per-second rate
     * @param overThrottledFraction share of seconds below half the ideal rate for the current capacity
     * @param overloadedFraction share of seconds whose load-induced latency exceeds twice the target (safety)
     * @param p99LoadLatencyMs p99 of the load-induced latency (safety)
     */
    record Result(double reversalsPerHour, double meanRps, double coefficientOfVariation,
                  double overThrottledFraction, double overloadedFraction, double p99LoadLatencyMs) {

        static Result of(List<Double> rps, List<Boolean> overloaded, List<Double> loadLatency, Workload workload,
            AdaptiveRpsController controller) {
            int reversals = 0;
            int lastSign = 0;
            for (int index = 1; index < rps.size(); index++) {
                int sign = (int) Math.signum(rps.get(index) - rps.get(index - 1));
                if (sign != 0) {
                    if (lastSign != 0 && sign != lastSign) {
                        reversals++;
                    }
                    lastSign = sign;
                }
            }
            double mean = rps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = rps.stream().mapToDouble(value -> (value - mean) * (value - mean)).average().orElse(0);
            int throttled = 0;
            for (int index = 0; index < rps.size(); index++) {
                double ideal = workload.capacity(WARM_UP_SECONDS + index) / 2;
                throttled += rps.get(index) < ideal / 2 ? 1 : 0;
            }
            double overload = overloaded.stream().filter(Boolean::booleanValue).count() / (double) overloaded.size();
            List<Double> sortedLatency = loadLatency.stream().sorted().toList();
            double p99 = sortedLatency.get((int) Math.ceil(0.99 * sortedLatency.size()) - 1);
            double hours = rps.size() / 3600.0;
            return new Result(reversals / hours, mean, Math.sqrt(variance) / mean, throttled / (double) rps.size(),
                overload, p99);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                "reversals/h=%.0f meanRps=%.1f cv=%.2f overThrottled=%.1f%% overloaded=%.1f%% p99LoadLatency=%.0fms",
                reversalsPerHour, meanRps, coefficientOfVariation, 100 * overThrottledFraction,
                100 * overloadedFraction, p99LoadLatencyMs);
        }
    }
}
