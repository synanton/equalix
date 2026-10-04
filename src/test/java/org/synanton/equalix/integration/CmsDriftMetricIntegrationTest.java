package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;
import org.synanton.equalix.domain.service.WatchdogService;

/**
 * EQX-5: the watchdog publishes {@code equalix.cms.estimation.drift{fairnessKey}} before rebuilding the sketch,
 * and removes the series once the key stops drifting.
 *
 * <p>Drift is injected as a phantom +3 on an idle key, applied outside any transaction, as a crash between the
 * database commit and the sketch update would leave it. The rebuild after the measurement clears it.
 */
@AutoConfigureObservability
class CmsDriftMetricIntegrationTest extends BaseIntegrationTest {

    private static final byte[] PAYLOAD = "drift".getBytes();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private TaskCompletionPort taskCompletion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Autowired
    private WatchdogService watchdogService;

    @Autowired
    private CMSProviderPort cms;

    @Test
    void shouldPublishDriftPerKeyAndRemoveItWhenDriftClears() throws Exception {
        String prefix = "drift-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String busyKey = prefix + "busy";
        String idleKey = prefix + "idle";
        for (int index = 0; index < 3; index++) {
            taskIngestion.createTask(busyKey, BigDecimal.ONE, PAYLOAD, false, null, null, false);
        }
        UUID idleTask = taskIngestion.createTask(idleKey, BigDecimal.ONE, PAYLOAD, false, null, null, false).getId();
        priorityCalculatorService.run();
        dispatcherService.dispatch();
        taskCompletion.completeTask(idleTask, true, null, null);
        cms.add(idleKey, 3);

        watchdogService.reconcile();

        // The idle key has 0 tasks in flight but is estimated at 3; the busy key is exact.
        assertThat(scrape())
            .contains("equalix_cms_estimation_drift{fairnessKey=\"" + idleKey + "\",layer=\"key\"} 3.0")
            .doesNotContain("fairnessKey=\"" + busyKey + "\"")
            .contains("equalix_cms_estimation_drift_max 3.0")
            .contains("equalix_cms_estimation_drift_min 0.0")
            .contains("equalix_cms_estimation_drift_keys 1.0")
            .contains("equalix_cms_estimation_drift_keys_sampled 2.0")
            .contains("equalix_cms_estimation_drift_absolute 3.0")
            .contains("equalix_cms_estimation_drift_timestamp_seconds " + (double) clock.instant().getEpochSecond());

        // The first run rebuilt the sketch from the task table, so the next measurement finds no drift.
        watchdogService.reconcile();

        assertThat(scrape())
            .doesNotContain("fairnessKey=\"" + idleKey + "\"")
            .contains("equalix_cms_estimation_drift_keys 0.0");
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus").header("X-API-Key", "test-api-key"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }
}
