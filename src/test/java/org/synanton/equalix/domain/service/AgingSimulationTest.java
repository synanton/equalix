package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.AgingPolicy;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.fairness.FairnessStatistics;

/**
 * EQX-4: simulates how each aging policy trades long-wait promotion against short-term weighted shares.
 *
 * <p>Tenant {@code large} (weight 9) is kept continuously backlogged; tenant {@code small} (weight 1) is either
 * also backlogged (steady state) or idle until it submits one burst (structural backlog). The scheduler serves
 * {@value #CAPACITY_PER_SECOND} tasks per simulated second using the production {@link VirtualTimeService}
 * (finish tags, aged V advance) and {@link AgingService} ranking over the whole queue, which is what the
 * dispatcher's candidate pool approximates.
 *
 * <p>Every policy is calibrated to give the same credit after {@value #CALIBRATION_WAIT_SECONDS} s of waiting,
 * equal to the virtual cost of {@value #CALIBRATION_TASKS} weight-1 tasks, so the policies differ only in the
 * shape of A(W).
 */
@Slf4j
class AgingSimulationTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final double QUANTUM = 1000.0;
    private static final int CAPACITY_PER_SECOND = 10;
    private static final int QUEUE_DEPTH_PER_TENANT = 50;
    private static final double CALIBRATION_WAIT_SECONDS = 30.0;
    private static final double CALIBRATION_TASKS = 10.0;
    private static final double POWER_GAMMA = 2.0;

    private static final int STEADY_SECONDS = 2_000;
    private static final int STEADY_WARM_UP_SECONDS = 200;
    private static final int BURST_START_SECOND = 100;
    private static final int BURST_SIZE = 300;
    private static final int BURST_SECONDS = 1_000;
    private static final int SHORT_TERM_WINDOW = 100;

    private static final String SMALL = "small";
    private static final String LARGE = "large";
    private static final Map<String, BigDecimal> WEIGHTS = new LinkedHashMap<>();

    private static final Map<AgingPolicy, SteadyResult> STEADY = new EnumMap<>(AgingPolicy.class);
    private static final Map<AgingPolicy, BurstResult> BURST = new EnumMap<>(AgingPolicy.class);

    static {
        WEIGHTS.put(SMALL, BigDecimal.ONE);
        WEIGHTS.put(LARGE, new BigDecimal("9"));
    }

    @BeforeAll
    static void simulateAllPolicies() {
        for (AgingPolicy policy : AgingPolicy.values()) {
            STEADY.put(policy, simulateSteadyBacklog(policy));
            BURST.put(policy, simulateBurst(policy));
            log.info("EQX-4 policy={} lambda={} | steady: share(small)={} sliding eps_max(W=100)={} "
                    + "| burst: small wait p50={}s max={}s, large share during burst={} min over W=100={}",
                policy, lambda(policy), STEADY.get(policy).smallShare(), STEADY.get(policy).slidingError(),
                BURST.get(policy).smallWaitP50(), BURST.get(policy).smallWaitMax(),
                BURST.get(policy).largeShareDuringBurst(), BURST.get(policy).largeMinShortTermShare());
        }
    }

    @Test
    void shouldPreserveLongTermWeightedSharesUnderContinuousBacklogForEveryPolicy() {
        // Constant queue depth gives each tenant a constant wait, so aging only adds a constant offset per tenant
        // and virtual time keeps the service rates proportional to the weights.
        STEADY.forEach((policy, result) -> {
            assertThat(result.smallShare()).as("%s long-term share of weight-1 tenant", policy)
                .isCloseTo(0.1, within(2.0 / result.dispatches()));
            assertThat(result.slidingError()).as("%s sliding eps_max(W=100)", policy)
                .isLessThanOrEqualTo(2.0 / SHORT_TERM_WINDOW);
        });
    }

    @Test
    void shouldServeBurstAtWeightedRateWithoutAging() {
        BurstResult none = BURST.get(AgingPolicy.NONE);

        // 300 tasks at 10% of 10 tasks/s drain in about 300 s; the heavy tenant keeps its 90%.
        assertThat(none.smallWaitMax()).isBetween(295.0, 305.0);
        assertThat(none.largeShareDuringBurst()).isBetween(0.89, 0.91);
    }

    @Test
    void shouldPromoteLongWaitingBurstTasks() {
        double baseline = BURST.get(AgingPolicy.NONE).smallWaitMax();

        assertThat(BURST.get(AgingPolicy.LINEAR).smallWaitMax()).isLessThan(0.9 * baseline);
        assertThat(BURST.get(AgingPolicy.POWER).smallWaitMax()).isLessThan(0.6 * baseline);
        assertThat(BURST.get(AgingPolicy.LOG).smallWaitMax()).isLessThanOrEqualTo(baseline);
    }

    @Test
    void shouldPromoteLongWaitsMostAggressivelyWithPowerAging() {
        assertThat(BURST.get(AgingPolicy.POWER).smallWaitMax())
            .isLessThan(BURST.get(AgingPolicy.LINEAR).smallWaitMax())
            .isLessThan(BURST.get(AgingPolicy.LOG).smallWaitMax());
    }

    @Test
    void shouldKeepHeavyTenantMajorityInEveryShortTermWindowDuringBurst() {
        // "Not completely fracturing" short-term quotas: in every window of 100 dispatches while the burst drains,
        // the weight-9 tenant still receives the majority of capacity.
        BURST.forEach((policy, result) -> assertThat(result.largeMinShortTermShare())
            .as("%s minimum weight-9 share over W=%d", policy, SHORT_TERM_WINDOW)
            .isGreaterThan(0.5));
    }

    private static SteadyResult simulateSteadyBacklog(AgingPolicy policy) {
        Scheduler scheduler = new Scheduler(policy);
        for (int round = 0; round < QUEUE_DEPTH_PER_TENANT; round++) {
            WEIGHTS.keySet().forEach(tenant -> scheduler.enqueue(tenant, START));
        }

        List<String> dispatchSequence = new ArrayList<>();
        for (int second = 1; second <= STEADY_SECONDS; second++) {
            Instant now = START.plusSeconds(second);
            for (Task task : scheduler.tick(now)) {
                if (second > STEADY_WARM_UP_SECONDS) {
                    dispatchSequence.add(task.getFairnessKey());
                }
                scheduler.enqueue(task.getFairnessKey(), now);
            }
        }

        FairnessStatistics statistics = new FairnessStatistics(WEIGHTS);
        return new SteadyResult(
            statistics.observedShares(dispatchSequence, dispatchSequence.size()).get(SMALL),
            statistics.slidingMaxError(dispatchSequence, SHORT_TERM_WINDOW),
            dispatchSequence.size());
    }

    private static BurstResult simulateBurst(AgingPolicy policy) {
        Scheduler scheduler = new Scheduler(policy);
        for (int round = 0; round < QUEUE_DEPTH_PER_TENANT; round++) {
            scheduler.enqueue(LARGE, START);
        }

        List<String> burstWindowSequence = new ArrayList<>();
        List<Double> smallWaits = new ArrayList<>();
        for (int second = 1; second <= BURST_SECONDS; second++) {
            Instant now = START.plusSeconds(second);
            if (second == BURST_START_SECOND) {
                for (int index = 0; index < BURST_SIZE; index++) {
                    scheduler.enqueue(SMALL, now);
                }
            }
            for (Task task : scheduler.tick(now)) {
                boolean burstDraining = second >= BURST_START_SECOND && smallWaits.size() < BURST_SIZE;
                if (burstDraining) {
                    burstWindowSequence.add(task.getFairnessKey());
                }
                if (SMALL.equals(task.getFairnessKey())) {
                    smallWaits.add(Duration.between(task.getCreatedAt(), now).toMillis() / 1000.0);
                } else {
                    scheduler.enqueue(LARGE, now);
                }
            }
        }
        assertThat(smallWaits).as("%s burst fully drained", policy).hasSize(BURST_SIZE);

        FairnessStatistics statistics = new FairnessStatistics(WEIGHTS);
        return new BurstResult(
            percentile(smallWaits, 0.50),
            percentile(smallWaits, 1.0),
            statistics.observedShares(burstWindowSequence, burstWindowSequence.size()).get(LARGE),
            minShortTermShare(burstWindowSequence, LARGE));
    }

    /** Lowest share of {@code tenant} over every contiguous window of {@link #SHORT_TERM_WINDOW} dispatches. */
    private static double minShortTermShare(List<String> sequence, String tenant) {
        double minShare = 1.0;
        int count = 0;
        for (int position = 0; position < sequence.size(); position++) {
            count += tenant.equals(sequence.get(position)) ? 1 : 0;
            if (position >= SHORT_TERM_WINDOW) {
                count -= tenant.equals(sequence.get(position - SHORT_TERM_WINDOW)) ? 1 : 0;
            }
            if (position >= SHORT_TERM_WINDOW - 1) {
                minShare = Math.min(minShare, (double) count / SHORT_TERM_WINDOW);
            }
        }
        return minShare;
    }

    /** λ such that A(CALIBRATION_WAIT_SECONDS) equals the virtual cost of CALIBRATION_TASKS weight-1 tasks. */
    private static double lambda(AgingPolicy policy) {
        double targetCredit = CALIBRATION_TASKS * QUANTUM;
        return switch (policy) {
            case NONE -> 0.0;
            case LINEAR -> targetCredit / CALIBRATION_WAIT_SECONDS;
            case LOG -> targetCredit / Math.log1p(CALIBRATION_WAIT_SECONDS);
            case POWER -> targetCredit / Math.pow(CALIBRATION_WAIT_SECONDS, POWER_GAMMA);
        };
    }

    private static double percentile(List<Double> values, double quantile) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    /** Mirrors the production pipeline: tag on enqueue, rank by aged priority, record dispatch with credit. */
    private static final class Scheduler {

        private final VirtualTimeService virtualTimeService;
        private final AgingService agingService;
        private final List<Task> queue = new ArrayList<>();

        Scheduler(AgingPolicy policy) {
            QueueProperties props = new QueueProperties();
            props.getVirtualTime().setQuantum(QUANTUM);
            props.getAging().setPolicy(policy);
            props.getAging().setLambda(lambda(policy));
            props.getAging().setGamma(POWER_GAMMA);
            props.getAging().setCandidatePoolSize(1_000);
            this.virtualTimeService = new VirtualTimeService(new InMemoryVirtualTimeRepository(START), props);
            this.agingService = new AgingService(props);
        }

        void enqueue(String tenant, Instant now) {
            Task task = new Task()
                .setId(UUID.randomUUID())
                .setFairnessKey(tenant)
                .setWeight(WEIGHTS.get(tenant))
                .setStatus(TaskStatus.QUEUED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
            double finishTag = virtualTimeService.assignFinishTag(task, virtualTimeService.currentSystemVirtualTime());
            queue.add(task.setPriority(Math.round(finishTag)));
        }

        List<Task> tick(Instant now) {
            List<Task> dispatched = agingService.rank(queue, CAPACITY_PER_SECOND, now);
            queue.removeAll(dispatched);
            virtualTimeService.recordDispatch(dispatched, task -> agingService.credit(task, now));
            return dispatched;
        }
    }

    private record SteadyResult(double smallShare, double slidingError, int dispatches) {
    }

    private record BurstResult(
        double smallWaitP50,
        double smallWaitMax,
        double largeShareDuringBurst,
        double largeMinShortTermShare) {
    }
}
