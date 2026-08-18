package org.synanton.equalix.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.service.AdaptiveRpsController;

@ExtendWith(MockitoExtension.class)
class MicrometerPerformanceMonitorAdapterTest {

    @Mock
    private AdaptiveRpsController adaptiveRpsController;

    private MeterRegistry registry;
    private MicrometerPerformanceMonitorAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(adaptiveRpsController.getCurrentRps()).thenReturn(12.0);
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerPerformanceMonitorAdapter(registry, adaptiveRpsController);
    }

    @Test
    void shouldRecordDurationAndFeedAdaptiveRpsOnSuccess() {
        adapter.recordCompletion("tenantA", 150L, true);

        var timer = registry.find("equalix.task.duration")
            .tag("success", "true")
            .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(150.0);

        var errorCounter = registry.find("equalix.task.errors").counter();
        assertThat(errorCounter).isNull();

        verify(adaptiveRpsController).recordCompletion(150L, true);
    }

    @Test
    void shouldIncrementErrorCounterOnFailure() {
        adapter.recordCompletion("tenantB", 250L, false);

        var errorCounter = registry.find("equalix.task.errors").counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(1.0);

        var timer = registry.find("equalix.task.duration")
            .tag("success", "false")
            .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);

        verify(adaptiveRpsController).recordCompletion(250L, false);
    }

    @Test
    void shouldRegisterCurrentRpsGauge() {
        assertThat(registry.find("equalix.adaptive.rps").gauge()).isNotNull();
        assertThat(registry.find("equalix.adaptive.rps").gauge().value()).isEqualTo(12.0);
    }
}
