package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.AgingPolicy;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;

class AgingServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");

    @Test
    void shouldBeDisabledForNonePolicy() {
        assertThat(service(AgingPolicy.NONE, 1000.0).isEnabled()).isFalse();
        assertThat(service(AgingPolicy.LOG, 1000.0).isEnabled()).isTrue();
    }

    @Test
    void shouldComputeCreditFromSecondsSinceCreation() {
        AgingService service = service(AgingPolicy.LINEAR, 10.0);
        Task task = task(1000L, NOW.minusMillis(2_500));

        assertThat(service.credit(task, NOW)).isEqualTo(25.0);
    }

    @Test
    void shouldTreatFutureCreationTimeAsZeroWait() {
        AgingService service = service(AgingPolicy.LINEAR, 10.0);

        assertThat(service.credit(task(1000L, NOW.plusSeconds(5)), NOW)).isZero();
    }

    @Test
    void shouldSortTasksWithoutPriorityLast() {
        AgingService service = service(AgingPolicy.LINEAR, 10.0);

        assertThat(service.effectivePriority(task(null, NOW.minusSeconds(3600)), NOW))
            .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void shouldRankByAgedPriorityAndKeepBestLimit() {
        AgingService service = service(AgingPolicy.LINEAR, 100.0);
        Task freshFront = task(1_000L, NOW);                        // effective 1000
        Task agedBack = task(5_000L, NOW.minusSeconds(45));        // effective 500
        Task freshBack = task(3_000L, NOW);                         // effective 3000

        List<Task> ranked = service.rank(List.of(freshFront, freshBack, agedBack), 2, NOW);

        assertThat(ranked).containsExactly(agedBack, freshFront);
    }

    @Test
    void shouldBreakTiesByArrivalThenId() {
        AgingService service = service(AgingPolicy.NONE, 0.0);
        Task later = task(1_000L, NOW.minusSeconds(1));
        Task earlier = task(1_000L, NOW.minusSeconds(2));
        UUID smallerId = new UUID(0L, 1L);
        UUID largerId = new UUID(0L, 2L);
        Task sameTimeLargerId = task(1_000L, NOW.minusSeconds(1)).setId(largerId);
        Task sameTimeSmallerId = task(1_000L, NOW.minusSeconds(1)).setId(smallerId);

        assertThat(service.rank(List.of(later, earlier), 2, NOW)).containsExactly(earlier, later);
        assertThat(service.rank(List.of(sameTimeLargerId, sameTimeSmallerId), 2, NOW))
            .containsExactly(sameTimeSmallerId, sameTimeLargerId);
    }

    @Test
    void shouldNeverUseAPoolSmallerThanFreeSlots() {
        AgingService service = service(AgingPolicy.LOG, 1000.0);

        assertThat(service.candidatePoolSize(10)).isEqualTo(200);
        assertThat(service.candidatePoolSize(500)).isEqualTo(500);
    }

    static AgingService service(AgingPolicy policy, double lambda) {
        QueueProperties props = new QueueProperties();
        props.getAging().setPolicy(policy);
        props.getAging().setLambda(lambda);
        props.getAging().setGamma(2.0);
        props.getAging().setCandidatePoolSize(200);
        return new AgingService(props);
    }

    private static Task task(Long priority, Instant createdAt) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey("clientA")
            .setStatus(TaskStatus.QUEUED)
            .setPriority(priority)
            .setCreatedAt(createdAt)
            .setUpdatedAt(createdAt);
    }
}
