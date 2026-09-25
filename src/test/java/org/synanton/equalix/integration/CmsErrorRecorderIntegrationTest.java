package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import org.synanton.equalix.domain.model.CmsErrorStatistics;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.service.CmsErrorRecorder;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

/**
 * EQX-2: the error recorder compares the live sketch with in-flight tasks and publishes Prometheus metrics.
 *
 * <p>Keys ending in "Aa" and "BB" with the same prefix have equal {@code String.hashCode()}, so they share every
 * sketch cell: the idle key is overestimated by the other key's in-flight count.
 *
 * <p>{@link AutoConfigureObservability} is required because Spring Boot disables metrics export in tests.
 */
@AutoConfigureObservability
class CmsErrorRecorderIntegrationTest extends BaseIntegrationTest {

    private static final byte[] PAYLOAD = "cms".getBytes();

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
    private CmsErrorRecorder cmsErrorRecorder;

    @Test
    void shouldSampleEstimationErrorAndPublishPrometheusMetrics() throws Exception {
        String prefix = "cms-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String busyKey = prefix + "BB";
        String idleKey = prefix + "Aa";
        assertThat(busyKey.hashCode()).isEqualTo(idleKey.hashCode());
        for (int index = 0; index < 3; index++) {
            taskIngestion.createTask(busyKey, BigDecimal.ONE, PAYLOAD, false, null, null, false);
        }
        UUID idleTask = taskIngestion.createTask(idleKey, BigDecimal.ONE, PAYLOAD, false, null, null, false).getId();
        List<UUID> sent = new ArrayList<>();
        doAnswer(invocation -> sent.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());
        priorityCalculatorService.run();
        dispatcherService.dispatch();
        taskCompletion.completeTask(idleTask, true, null, null);

        CmsErrorStatistics statistics = cmsErrorRecorder.sample();

        // Busy key: 3 in flight and estimated 3. Idle key: 0 in flight but estimated 3 through the shared cells.
        assertThat(sent).hasSize(4);
        assertThat(List.of(statistics.count(), statistics.min(), statistics.max()))
            .containsExactly(2L, 0L, 3L);
        String metrics = mockMvc.perform(get("/actuator/prometheus").header("X-API-Key", "test-api-key"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(metrics)
            .contains("equalix_cms_estimation_error_count{direction=\"over\"}")
            .contains("equalix_cms_estimation_error_count{direction=\"exact\"}")
            .contains("equalix_cms_estimation_error_magnitude{quantile=\"0.99\"}");
    }
}
