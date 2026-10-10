package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.synanton.equalix.adapter.out.database.entity.ClientCountsEntity;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

/** CMS updates follow the transaction outcome: applied on commit, discarded on rollback. */
class CmsTransactionConsistencyIntegrationTest extends BaseIntegrationTest {

    private static final byte[] PAYLOAD = "tx".getBytes();

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Autowired
    private CMSProviderPort cms;

    @Test
    void shouldKeepAccountingConsistentWhenSendFailsAfterCommit() {
        String fairnessKey = "rollback-" + UUID.randomUUID();
        UUID taskId = taskIngestion.createTask(fairnessKey, BigDecimal.ONE, PAYLOAD, false, null, null, false).getId();
        priorityCalculatorService.run();
        doThrow(new IllegalStateException("executor unavailable"))
            .when(remoteExecutor).send(any(), any(), any());

        // Sends fire after commit, so a throwing executor cannot roll the dispatch back.
        // The failure surfaces, but rows, counts, and sketch agree: the slot is held and
        // the timeout sweep owns recovery. (The production executor never throws — it
        // logs send failures — so this path exists only for executor implementations
        // whose send is synchronous and fallible.)
        assertThatThrownBy(() -> dispatcherService.dispatch()).isInstanceOf(IllegalStateException.class);

        assertThat(taskJpaRepository.findById(taskId)).get()
            .extracting(TaskEntity::getStatus)
            .isEqualTo(TaskStatus.DISPATCHED);
        assertThat(cms.estimateCount(fairnessKey)).as("slot held consistently").isEqualTo(1);
        assertThat(clientCountsJpaRepository.findById(fairnessKey)).get()
            .extracting(ClientCountsEntity::getInFlightCount)
            .isEqualTo(1);
    }

    @Test
    void shouldCountDispatchOnceTransactionCommits() {
        String fairnessKey = "commit-" + UUID.randomUUID();
        taskIngestion.createTask(fairnessKey, BigDecimal.ONE, PAYLOAD, false, null, null, false);
        priorityCalculatorService.run();

        dispatcherService.dispatch();

        assertThat(cms.estimateCount(fairnessKey)).isEqualTo(1);
    }
}
