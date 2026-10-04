package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.AgingPolicy;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.model.HierarchyNodeState;
import org.synanton.equalix.domain.model.QueuedLeaf;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.HierarchyStateRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class HierarchicalDispatchPlannerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private HierarchyStateRepositoryPort hierarchyStateRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private AdaptiveRpsController adaptiveRpsController;
    @Mock
    private PerformanceMonitorPort performanceMonitor;

    private HierarchicalDispatchPlanner planner;

    @BeforeEach
    void setUp() {
        QueueProperties props = new QueueProperties();
        props.getVirtualTime().setQuantum(1000);
        props.getAging().setPolicy(AgingPolicy.NONE);
        planner = new HierarchicalDispatchPlanner(
            FairnessHierarchyTest.hierarchy(FairnessMode.HIERARCHICAL, Map.of()), taskRepository,
            hierarchyStateRepository, cms, adaptiveRpsController, performanceMonitor, props);
    }

    @Test
    void shouldLockPlannedTasksPerLeafAndReturnThemInPickOrder() {
        when(taskRepository.findQueuedLeaves()).thenReturn(List.of(
            new QueuedLeaf("acme/hot", 100, 0, 1.0, 0), new QueuedLeaf("acme/cold", 2, 0, 1.0, 0)));
        when(hierarchyStateRepository.findByKeys(anyCollection())).thenReturn(Map.of());
        Task cold1 = task("acme/cold");
        Task cold2 = task("acme/cold");
        Task hot1 = task("acme/hot");
        Task hot2 = task("acme/hot");
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("acme/cold", 2);
        limits.put("acme/hot", 2);
        when(taskRepository.findAndLockQueuedHeads(limits)).thenReturn(List.of(cold1, cold2, hot1, hot2));

        HierarchicalDispatchPlanner.Selection selection = planner.select(4, null);

        assertThat(selection.tasks()).containsExactly(cold1, hot1, cold2, hot2);
    }

    @Test
    void shouldSkipPicksWhoseTaskWasLockedElsewhere() {
        when(taskRepository.findQueuedLeaves()).thenReturn(List.of(new QueuedLeaf("acme/a", 3, 0, 1.0, 0)));
        when(hierarchyStateRepository.findByKeys(anyCollection())).thenReturn(Map.of());
        Task only = task("acme/a");
        when(taskRepository.findAndLockQueuedHeads(Map.of("acme/a", 3))).thenReturn(List.of(only));

        assertThat(planner.select(3, null).tasks()).containsExactly(only);
    }

    @Test
    void shouldReturnEmptySelectionWithoutBacklog() {
        when(taskRepository.findQueuedLeaves()).thenReturn(List.of());

        assertThat(planner.select(5, null).tasks()).isEmpty();
        verifyNoInteractions(hierarchyStateRepository);
    }

    @Test
    void shouldPersistChargesFloorsAndCountersForDispatchedTasks() {
        when(taskRepository.findQueuedLeaves()).thenReturn(List.of(new QueuedLeaf("acme/a", 5, 0, 2.0, 0)));
        when(hierarchyStateRepository.findByKeys(anyCollection())).thenReturn(Map.of(
            "acme/", new HierarchyNodeState("acme/", 0, 500)));
        Task first = task("acme/a").setWeight(new BigDecimal("2"));
        Task second = task("acme/a").setWeight(new BigDecimal("2"));
        when(taskRepository.findAndLockQueuedHeads(Map.of("acme/a", 2))).thenReturn(List.of(first, second));
        HierarchicalDispatchPlanner.Selection selection = planner.select(2, null);

        planner.recordDispatch(selection);

        // acme/ (weight 1) is charged 2 × 1000; the leaf (task weight 2) 2 × 500, from the parent floor 500.
        verify(hierarchyStateRepository).chargeVirtualTime("acme/", 0.0, 2_000.0);
        verify(hierarchyStateRepository).chargeVirtualTime("acme/a", 500.0, 1_000.0);
        verify(hierarchyStateRepository).raiseChildrenFloor("", 2_000.0);
        verify(hierarchyStateRepository).raiseChildrenFloor("acme/", 1_500.0);
        verify(performanceMonitor, times(2)).recordHierarchicalDispatch("organization", "acme/");
    }

    @Test
    void shouldChargeSequentialDispatchFromParentFloors() {
        when(hierarchyStateRepository.findByKeys(anyCollection())).thenReturn(Map.of(
            "", new HierarchyNodeState("", 0, 100), "acme/", new HierarchyNodeState("acme/", 150, 300)));

        planner.recordSequentialDispatch(task("acme/a"));

        InOrder order = inOrder(hierarchyStateRepository);
        order.verify(hierarchyStateRepository).chargeVirtualTime("acme/", 100.0, 1_000.0);
        order.verify(hierarchyStateRepository).chargeVirtualTime("acme/a", 300.0, 1_000.0);
    }

    private static Task task(String fairnessKey) {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(BigDecimal.ONE)
            .setStatus(TaskStatus.QUEUED)
            .setPriority(1_000L)
            .setCreatedAt(NOW)
            .setUpdatedAt(NOW);
    }
}
