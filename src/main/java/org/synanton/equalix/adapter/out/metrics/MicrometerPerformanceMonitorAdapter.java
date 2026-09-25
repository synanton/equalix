package org.synanton.equalix.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.model.CmsDriftReport;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.service.AdaptiveRpsController;

@Component
public class MicrometerPerformanceMonitorAdapter implements PerformanceMonitorPort {

    private static final String DRIFT = "equalix.cms.estimation.drift";

    private final MeterRegistry meterRegistry;
    private final AdaptiveRpsController adaptiveRpsController;

    // Gauges hold weak references to their state, so every value object is kept here.
    private final Map<String, DriftGauge> driftGauges = new HashMap<>();
    private final AtomicLong driftMax = new AtomicLong();
    private final AtomicLong driftMin = new AtomicLong();
    private final AtomicLong driftAbsoluteTotal = new AtomicLong();
    private final AtomicLong driftKeys = new AtomicLong();
    private final AtomicLong driftKeysSampled = new AtomicLong();
    private final AtomicLong driftMeasuredAtEpochSecond = new AtomicLong();
    private final Map<String, AtomicLong> driftByLayer = new HashMap<>();

    public MicrometerPerformanceMonitorAdapter(
        MeterRegistry meterRegistry,
        AdaptiveRpsController adaptiveRpsController
    ) {
        this.meterRegistry = meterRegistry;
        this.adaptiveRpsController = adaptiveRpsController;
        Gauge.builder("equalix.adaptive.rps", adaptiveRpsController, AdaptiveRpsController::getCurrentRps)
            .register(meterRegistry);
        registerDriftAggregate(DRIFT + ".max", driftMax, "Largest CMS overestimate at the last watchdog run");
        registerDriftAggregate(DRIFT + ".min", driftMin,
            "Largest CMS underestimate (negative) at the last watchdog run");
        registerDriftAggregate(DRIFT + ".absolute", driftAbsoluteTotal,
            "Sum of |drift| over all sampled keys at the last watchdog run");
        registerDriftAggregate(DRIFT + ".keys", driftKeys, "Keys with non-zero CMS drift at the last watchdog run");
        registerDriftAggregate(DRIFT + ".keys.sampled", driftKeysSampled,
            "Keys compared with the task table at the last watchdog run");
        Gauge.builder(DRIFT + ".timestamp", driftMeasuredAtEpochSecond, AtomicLong::get)
            .description("Epoch second of the last watchdog drift measurement on this instance")
            .baseUnit("seconds")
            .register(meterRegistry);
    }

    @Override
    public void recordCompletion(String fairnessKey, long durationMs, boolean success) {
        Timer.builder("equalix.task.duration")
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        if (!success) {
            Counter.builder("equalix.task.errors")
                .register(meterRegistry)
                .increment();
        }

        adaptiveRpsController.recordCompletion(durationMs, success);
    }

    /**
     * Publishes {@code equalix.cms.estimation.error} tagged by direction (over/under/exact), whose counts give the
     * over- and underestimation frequencies, and the untagged {@code equalix.cms.estimation.error.magnitude}
     * with p50/p95/p99 of {@code |e_k|}. Magnitudes are recorded because distribution summaries drop negative values.
     */
    @Override
    public void recordCmsEstimationError(long error) {
        String direction = error > 0 ? "over" : error < 0 ? "under" : "exact";
        DistributionSummary.builder("equalix.cms.estimation.error")
            .description("CMS in-flight estimation error |e_k| by direction")
            .tag("direction", direction)
            .register(meterRegistry)
            .record(Math.abs(error));
        DistributionSummary.builder("equalix.cms.estimation.error.magnitude")
            .description("CMS in-flight estimation error |e_k|")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)
            .record(Math.abs(error));
    }

    /**
     * Sets {@code equalix.cms.estimation.drift{fairnessKey}} for the reported keys and removes the series of keys
     * that are no longer reported, so a key that stops drifting disappears instead of keeping a stale value.
     */
    @Override
    public synchronized void publishCmsDrift(CmsDriftReport report) {
        Map<String, Long> reported = report.topDrifting();
        Iterator<Map.Entry<String, DriftGauge>> existing = driftGauges.entrySet().iterator();
        while (existing.hasNext()) {
            Map.Entry<String, DriftGauge> entry = existing.next();
            if (!reported.containsKey(entry.getKey())) {
                meterRegistry.remove(entry.getValue().gauge());
                existing.remove();
            }
        }
        reported.forEach((fairnessKey, drift) -> driftGauges
            .computeIfAbsent(fairnessKey, key -> registerDriftGauge(key, report.layers().get(key)))
            .value().set(drift));
        driftByLayer.values().forEach(total -> total.set(0));
        report.absoluteDriftByLayer().forEach((layer, total) ->
            driftByLayer.computeIfAbsent(layer, this::registerLayerDriftGauge).set(total));

        driftMax.set(report.maxDrift());
        driftMin.set(report.minDrift());
        driftAbsoluteTotal.set(report.absoluteDriftTotal());
        driftKeys.set(report.keysDrifting());
        driftKeysSampled.set(report.keysSampled());
        driftMeasuredAtEpochSecond.set(report.measuredAt().getEpochSecond());
    }

    @Override
    public void recordHierarchicalDispatch(String layer, String nodeKey) {
        Counter.builder("equalix.hierarchy.dispatches")
            .description("Tasks dispatched per hierarchy node, for layers within app.hierarchical.metrics-depth")
            .tag("layer", layer)
            .tag("node", nodeKey)
            .register(meterRegistry)
            .increment();
    }

    private DriftGauge registerDriftGauge(String fairnessKey, String layer) {
        AtomicLong value = new AtomicLong();
        Gauge gauge = Gauge.builder(DRIFT, value, AtomicLong::get)
            .description("CMS estimate minus in-flight tasks per fairness key or hierarchy node at the last "
                + "watchdog run")
            .tag("fairnessKey", fairnessKey)
            .tag("layer", layer == null ? CmsDriftReport.FLAT_LAYER : layer)
            .register(meterRegistry);
        return new DriftGauge(gauge, value);
    }

    private AtomicLong registerLayerDriftGauge(String layer) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(DRIFT + ".layer.absolute", value, AtomicLong::get)
            .description("Sum of |drift| per hierarchy layer at the last watchdog run")
            .tag("layer", layer)
            .register(meterRegistry);
        return value;
    }

    private void registerDriftAggregate(String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::get)
            .description(description)
            .register(meterRegistry);
    }

    private record DriftGauge(Gauge gauge, AtomicLong value) {
    }
}
