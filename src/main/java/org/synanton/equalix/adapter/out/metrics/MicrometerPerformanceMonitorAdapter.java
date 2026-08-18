package org.synanton.equalix.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
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
}
