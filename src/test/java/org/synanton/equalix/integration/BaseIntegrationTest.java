package org.synanton.equalix.integration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.synanton.equalix.adapter.in.schedule.ClientBlockRecoveryScheduler;
import org.synanton.equalix.adapter.in.schedule.DispatcherScheduler;
import org.synanton.equalix.adapter.in.schedule.PriorityCalculatorScheduler;
import org.synanton.equalix.adapter.in.schedule.ResultPassthroughRecoveryScheduler;
import org.synanton.equalix.adapter.in.schedule.SequentialDispatcherScheduler;
import org.synanton.equalix.adapter.in.schedule.TaskTimeoutScheduler;
import org.synanton.equalix.adapter.in.schedule.WatchdogScheduler;
import org.synanton.equalix.adapter.out.database.ClientCountsJpaRepository;
import org.synanton.equalix.adapter.out.database.ClientSequenceStateJpaRepository;
import org.synanton.equalix.adapter.out.database.TaskJpaRepository;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @MockBean
    protected RemoteExecutorPort remoteExecutor;

    // No @Scheduled job may fire in integration tests: jobs are driven
    // explicitly (direct service calls) so status assertions are
    // deterministic. Mocking the scheduler components replaces their beans;
    // no @Scheduled method on a mock is ever invoked. NOTE: this exists
    // because spring.task.scheduling.enabled is NOT a real Boot property
    // (TaskSchedulingProperties has no `enabled` field — verified against
    // spring-boot-autoconfigure 3.5.16) — the flag previously carried in
    // test application.yml was silently ignored, and a 100ms calculator
    // tick raced ingest assertions (thread [scheduling-1] observed tagging
    // tasks mid-test). Do not rely on the yml flag; these mocks are the
    // mechanism.
    @MockBean
    protected DispatcherScheduler dispatcherScheduler;
    @MockBean
    protected PriorityCalculatorScheduler priorityCalculatorScheduler;
    @MockBean
    protected WatchdogScheduler watchdogScheduler;
    @MockBean
    protected TaskTimeoutScheduler taskTimeoutScheduler;
    @MockBean
    protected SequentialDispatcherScheduler sequentialDispatcherScheduler;
    @MockBean
    protected ClientBlockRecoveryScheduler clientBlockRecoveryScheduler;
    @MockBean
    protected ResultPassthroughRecoveryScheduler resultPassthroughRecoveryScheduler;

    @Autowired
    protected TaskJpaRepository taskJpaRepository;

    @Autowired
    protected ClientCountsJpaRepository clientCountsJpaRepository;

    @Autowired
    protected ClientSequenceStateJpaRepository sequenceStateJpaRepository;

    @BeforeEach
    void cleanUp() {
        clientCountsJpaRepository.deleteAll();
        sequenceStateJpaRepository.deleteAll();
        taskJpaRepository.deleteAll();
    }

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        public Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
