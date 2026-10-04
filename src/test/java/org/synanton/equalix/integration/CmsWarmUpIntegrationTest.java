package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.synanton.equalix.adapter.in.startup.CmsWarmUpListener;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

/** A restarted instance must not start with an empty sketch while tasks are still in flight. */
class CmsWarmUpIntegrationTest extends BaseIntegrationTest {

    private static final byte[] PAYLOAD = "warm".getBytes();

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Autowired
    private CMSProviderPort cms;

    @Autowired
    private CmsWarmUpListener cmsWarmUpListener;

    @Test
    void shouldRebuildSketchFromInFlightTasksWhenApplicationIsReady() {
        String fairnessKey = "warm-" + UUID.randomUUID();
        for (int index = 0; index < 2; index++) {
            taskIngestion.createTask(fairnessKey, BigDecimal.ONE, PAYLOAD, false, null, null, false);
        }
        priorityCalculatorService.run();
        dispatcherService.dispatch();
        cms.rebuild(Map.of()); // what a freshly started instance holds

        cmsWarmUpListener.onApplicationReady();

        assertThat(cms.estimateCount(fairnessKey)).isEqualTo(2);
    }
}
