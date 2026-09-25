package org.synanton.equalix.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.service.AdaptiveRpsController;

@Component
public class MicrometerPerformanceMonitorAdapter implements PerformanceMonitorPort {

    private final MeterRegistry meterRegistry;
    private final AdaptiveRpsController adaptiveRpsController;

    public MicrometerPerformanceMonitorAdapter(
        MeterRegistry meterRegistry,
        AdaptiveRpsController adaptiveRpsController
    ) {
        this.meterRegistry = meterRegistry;
        this.adaptiveRpsController = adaptiveRpsController;
        Gauge.builder("equalix.adaptive.rps", adaptiveRpsController, AdaptiveRpsController::getCurrentRps)
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
}
