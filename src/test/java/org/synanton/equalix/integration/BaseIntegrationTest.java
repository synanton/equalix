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
