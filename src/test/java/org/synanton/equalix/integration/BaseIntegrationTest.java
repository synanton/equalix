package org.synanton.equalix.integration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.synanton.equalix.adapter.out.database.ClientCountsJpaRepository;
import org.synanton.equalix.adapter.out.database.ClientSequenceStateJpaRepository;
import org.synanton.equalix.adapter.out.database.ClientVirtualTimeJpaRepository;
import org.synanton.equalix.adapter.out.database.HierarchyNodeJpaRepository;
import org.synanton.equalix.adapter.out.database.SchedulerVirtualClockJpaRepository;
import org.synanton.equalix.adapter.out.database.TaskJpaRepository;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(BaseIntegrationTest.ClockTestConfig.class)
public abstract class BaseIntegrationTest {

    @MockBean
    protected RemoteExecutorPort remoteExecutor;

    // No @Scheduled job fires in integration tests: SchedulingConfig is
    // conditional on app.scheduling.enabled, which test application.yml
    // sets to false — jobs are driven explicitly (direct service calls) so
    // status assertions are deterministic. (History: spring.task.scheduling
    // .enabled is not a real Boot property and was silently ignored, letting
    // a 100ms calculator tick race ingest assertions. The app.scheduling
    // toggle is the mechanism; do not re-add scheduler mocks unless
    // ShedLock contention is empirically observed.)

    @Autowired
    protected TaskJpaRepository taskJpaRepository;

    @Autowired
    protected ClientCountsJpaRepository clientCountsJpaRepository;

    @Autowired
    protected ClientSequenceStateJpaRepository sequenceStateJpaRepository;

    @Autowired
    protected ClientVirtualTimeJpaRepository clientVirtualTimeJpaRepository;

    @Autowired
    protected SchedulerVirtualClockJpaRepository schedulerVirtualClockJpaRepository;

    @Autowired
    protected HierarchyNodeJpaRepository hierarchyNodeJpaRepository;

    /** Application clock; frozen at context start and reset before each test unless a test advances it. */
    @Autowired
    protected AdjustableClock clock;

    @BeforeEach
    void cleanUp() {
        clock.reset();
        clientCountsJpaRepository.deleteAllInBatch();
        sequenceStateJpaRepository.deleteAllInBatch();
        clientVirtualTimeJpaRepository.deleteAllInBatch();
        schedulerVirtualClockJpaRepository.deleteAllInBatch();
        hierarchyNodeJpaRepository.deleteAllInBatch();
        taskJpaRepository.deleteAllInBatch();
    }

    /**
     * Starts at real time so that SQL comparing application timestamps with database {@code now()} (starvation,
     * timeouts) behaves as in production.
     */
    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        public AdjustableClock adjustableClock() {
            return new AdjustableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
    }
}
