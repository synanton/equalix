package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;
import org.synanton.equalix.domain.service.WatchdogService;

/**
 * EQX-5: the watchdog publishes {@code equalix.cms.estimation.drift{fairnessKey}} before rebuilding the sketch,
 * and removes the series once the key stops drifting.
 *
 * <p>Keys ending in "Aa" and "BB" with the same prefix have equal {@code String.hashCode()} and share every
 * sketch cell (see invariants §20). That makes a real, reproducible drift without injecting faults, and shows
 * that a rebuild does not remove collision drift.
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

    @Test
    void shouldPublishDriftPerKeyAndRemoveItWhenDriftClears() throws Exception {
        String prefix = "drift-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String busyKey = prefix + "BB";
        String idleKey = prefix + "Aa";
        List<UUID> busyTasks = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            busyTasks.add(taskIngestion.createTask(busyKey, BigDecimal.ONE, PAYLOAD, false, null, null, false).getId());
        }
        UUID idleTask = taskIngestion.createTask(idleKey, BigDecimal.ONE, PAYLOAD, false, null, null, false).getId();
        priorityCalculatorService.run();
        dispatcherService.dispatch();
        taskCompletion.completeTask(idleTask, true, null, null);

        watchdogService.reconcile();

        // The idle key has 0 tasks in flight but shares all cells with the busy key's 3; the busy key is exact.
        assertThat(scrape())
            .contains("equalix_cms_estimation_drift{fairnessKey=\"" + idleKey + "\"} 3.0")
            .doesNotContain("fairnessKey=\"" + busyKey + "\"")
            .contains("equalix_cms_estimation_drift_max 3.0")
            .contains("equalix_cms_estimation_drift_min 0.0")
            .contains("equalix_cms_estimation_drift_keys 1.0")
            .contains("equalix_cms_estimation_drift_keys_sampled 2.0")
            .contains("equalix_cms_estimation_drift_timestamp_seconds " + (double) clock.instant().getEpochSecond());

        for (UUID taskId : busyTasks) {
            taskCompletion.completeTask(taskId, true, null, null);
        }
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
