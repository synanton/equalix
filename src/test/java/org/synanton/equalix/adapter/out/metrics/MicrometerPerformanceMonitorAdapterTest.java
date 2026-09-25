package org.synanton.equalix.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.CmsDriftReport;
import org.synanton.equalix.domain.service.AdaptiveRpsController;

@ExtendWith(MockitoExtension.class)
class MicrometerPerformanceMonitorAdapterTest {

    private static final Instant DRIFT_TIME = Instant.parse("2026-01-01T00:05:00Z");

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

    @Test
    void shouldPublishCmsErrorByDirectionAndMagnitude() {
        adapter.recordCmsEstimationError(3L);
        adapter.recordCmsEstimationError(-2L);
        adapter.recordCmsEstimationError(0L);
        adapter.recordCmsEstimationError(1L);

        var over = registry.find("equalix.cms.estimation.error").tag("direction", "over").summary();
        var under = registry.find("equalix.cms.estimation.error").tag("direction", "under").summary();
        var exact = registry.find("equalix.cms.estimation.error").tag("direction", "exact").summary();
        var magnitude = registry.find("equalix.cms.estimation.error.magnitude").summary();
        assertThat(List.of(over.count(), under.count(), exact.count(), magnitude.count()))
            .containsExactly(2L, 1L, 1L, 4L);
        assertThat(List.of(over.totalAmount(), under.max(), magnitude.totalAmount()))
            .containsExactly(4.0, 2.0, 6.0);
    }

    @Test
    void shouldPublishPerKeyDriftAndAggregates() {
        adapter.publishCmsDrift(new CmsDriftReport(DRIFT_TIME, 40, 2, 3, -1, 4, Map.of("tenantA", 3L, "tenantB", -1L)));

        assertThat(driftGauges()).isEqualTo(Map.of("tenantA", 3.0, "tenantB", -1.0));
        assertThat(List.of(gauge("equalix.cms.estimation.drift.max"), gauge("equalix.cms.estimation.drift.min"),
            gauge("equalix.cms.estimation.drift.absolute.total"), gauge("equalix.cms.estimation.drift.keys"),
            gauge("equalix.cms.estimation.drift.keys.sampled"), gauge("equalix.cms.estimation.drift.timestamp")))
            .containsExactly(3.0, -1.0, 4.0, 2.0, 40.0, (double) DRIFT_TIME.getEpochSecond());
    }

    @Test
    void shouldRemoveSeriesOfKeysThatStoppedDriftingAndUpdateTheRest() {
        adapter.publishCmsDrift(new CmsDriftReport(DRIFT_TIME, 2, 2, 3, -1, 4, Map.of("tenantA", 3L, "tenantB", -1L)));

        adapter.publishCmsDrift(new CmsDriftReport(DRIFT_TIME.plusSeconds(300), 2, 1, 5, 0, 5,
            Map.of("tenantA", 5L)));

        assertThat(driftGauges()).isEqualTo(Map.of("tenantA", 5.0));
    }

    @Test
    void shouldExposeZeroAggregatesBeforeFirstWatchdogRun() {
        assertThat(driftGauges()).isEmpty();
        assertThat(List.of(gauge("equalix.cms.estimation.drift.keys"), gauge("equalix.cms.estimation.drift.timestamp")))
            .containsExactly(0.0, 0.0);
    }

    private Map<String, Double> driftGauges() {
        return registry.find("equalix.cms.estimation.drift").gauges().stream()
            .collect(Collectors.toMap(gauge -> gauge.getId().getTag("fairnessKey"), Gauge::value));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
