package org.synanton.equalix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskCompletionPort;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;
import org.synanton.equalix.domain.service.DispatchAckService;
import org.synanton.equalix.domain.service.DispatcherService;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

/**
 * DB-load benchmark: drives a fixed workload (single tenant, quota off, RPS control off)
 * through the full lifecycle and reports wall-clock time per phase. Used to compare
 * statement counts/timings before and after the write-path optimizations (bulk dispatch
 * UPDATE, targeted completion/ack UPDATEs, bulk promotion, batched counts).
 *
 * <p>Method: Testcontainers PostgreSQL, scheduling off, jobs driven explicitly. One
 * measured pass after the mock setup (no warmup iterations — JIT/DB-cache noise applies
 * equally to both sides of a before/after comparison on the same host). Timings print
 * to stdout; assertions cover correctness only, never performance.
 */
@TestPropertySource(properties = {
    "app.queue.max-per-client-quota=0",
    "app.queue.max-tasks-in-process=500",
    "app.queue.worker-poll-size=500",
    "app.queue.max-queued-time-ms=3153600000000"
})
class DbLoadBenchmarkTest extends BaseIntegrationTest {

    static final int TASKS = 400;
    private static final byte[] PAYLOAD = "benchmark".getBytes();

    /** Wraps the container DataSource in a statement counter (test-only, production untouched). */
    @TestConfiguration
    static class CountingDataSourceConfig {
        @Bean
        static BeanPostProcessor countingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof javax.sql.DataSource dataSource
                        && !java.lang.reflect.Proxy.isProxyClass(bean.getClass())) {
                        return CountingDataSource.wrap(dataSource);
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired
    private TaskIngestionPort taskIngestion;

    @Autowired
    private TaskCompletionPort taskCompletion;

    @Autowired
    private TaskRepositoryPort taskRepository;

    @Autowired
    private PriorityCalculatorService priorityCalculatorService;

    @Autowired
    private DispatcherService dispatcherService;

    @Autowired
    private DispatchAckService dispatchAckService;

    @Test
    void measureFullLifecycle() {
        String tenant = "bench-" + UUID.randomUUID().toString().substring(0, 8);
        List<UUID> sent = new ArrayList<>();
        doAnswer(invocation -> sent.add(invocation.getArgument(0)))
            .when(remoteExecutor).send(any(), any(), any());

        Timed ingest = timed(() -> {
            for (int i = 0; i < TASKS; i++) {
                taskIngestion.createTask(tenant, BigDecimal.ONE, PAYLOAD, false, null, null, false);
            }
        });
        long ingestStmts = ingest.stmts();

        Timed calc = timed(() -> {
            for (int i = 0; i < 10
                && taskRepository.findByStatus(TaskStatus.RECEIVED, TASKS).size() > 0; i++) {
                priorityCalculatorService.run();
            }
        });
        long calcStmts = calc.stmts();
        assertThat(taskRepository.findByStatus(TaskStatus.QUEUED, TASKS)).hasSize(TASKS);

        Timed dispatch = timed(() -> {
            for (int i = 0; i < 10 && sent.size() < TASKS; i++) {
                dispatcherService.dispatch();
            }
        });
        assertThat(sent).hasSize(TASKS);

        List<UUID> dispatched = new ArrayList<>(sent);
        Timed ack = timed(() -> dispatched.forEach(dispatchAckService::markCommitted));

        Timed complete = timed(() -> dispatched.forEach(
            id -> taskCompletion.completeTask(id, true, PAYLOAD, null)));
        assertThat(taskRepository.findByStatus(TaskStatus.SUCCEEDED, TASKS)).hasSize(TASKS);

        long totalMs = ingest.ms() + calc.ms() + dispatch.ms() + ack.ms() + complete.ms();
        long totalStmts = ingestStmts + calcStmts + dispatch.stmts() + ack.stmts() + complete.stmts();
        System.out.printf(
            "DBLOAD tasks=%d ingest=%dms/%dstmts (%.0f/s) calc=%dms/%dstmts (%.0f/s) "
                + "dispatch=%dms/%dstmts (%.0f/s) ack=%dms/%dstmts (%.0f/s) "
                + "complete=%dms/%dstmts (%.0f/s) total=%dms/%dstmts (%.0f/s)%n",
            TASKS,
            ingest.ms(), ingestStmts, rate(ingest.ms()),
            calc.ms(), calcStmts, rate(calc.ms()),
            dispatch.ms(), dispatch.stmts(), rate(dispatch.ms()),
            ack.ms(), ack.stmts(), rate(ack.ms()),
            complete.ms(), complete.stmts(), rate(complete.ms()),
            totalMs, totalStmts, rate(totalMs));
    }

    private record Timed(long ms, long stmts) {
    }

    private Timed timed(Runnable work) {
        CountingDataSource.reset();
        long start = System.nanoTime();
        work.run();
        return new Timed((System.nanoTime() - start) / 1_000_000, CountingDataSource.count());
    }

    private double rate(long ms) {
        return ms == 0 ? 0 : TASKS * 1000.0 / ms;
    }
}
