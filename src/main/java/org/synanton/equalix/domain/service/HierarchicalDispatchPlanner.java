package org.synanton.equalix.domain.service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.AgingPolicy;
import org.synanton.equalix.domain.model.HierarchyNode;
import org.synanton.equalix.domain.model.HierarchyNodeState;
import org.synanton.equalix.domain.model.QueuedLeaf;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.HierarchyStateRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * Dispatch-time side of hierarchical scheduling (EQX-7): selects and locks tasks with
 * {@link HierarchicalSelector}, and persists node virtual time for what was dispatched.
 *
 * <p>Selection is done at dispatch time because a node's fair share depends on which of its siblings are
 * backlogged right now, which a priority computed at queue time cannot know. Within a leaf, tasks keep the order
 * of their stored priority (flat virtual-time tag plus pressure).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchicalDispatchPlanner {

    private final FairnessHierarchy hierarchy;
    private final TaskRepositoryPort taskRepository;
    private final HierarchyStateRepositoryPort hierarchyStateRepository;
    private final CMSProviderPort cms;
    private final AdaptiveRpsController adaptiveRpsController;
    private final PerformanceMonitorPort performanceMonitor;
    private final QueueProperties queueProperties;

    @PostConstruct
    void warnAboutUnsupportedCombinations() {
        if (hierarchy.isEnabled() && queueProperties.getAging().getPolicy() != AgingPolicy.NONE) {
            log.warn("app.queue.aging.policy={} is ignored in hierarchical fairness mode; "
                + "the max-queued-time-ms promotion still applies", queueProperties.getAging().getPolicy());
        }
    }

    /** Selects up to {@code freeSlots} tasks and locks them, in dispatch order. */
    public Selection select(int freeSlots, @Nullable Integer maxPerClient) {
        List<QueuedLeaf> leaves = taskRepository.findQueuedLeaves();
        if (leaves.isEmpty()) {
            return new Selection(List.of(), null);
        }
        Set<String> nodeKeys = new HashSet<>();
        nodeKeys.add(FairnessHierarchy.ROOT);
        leaves.forEach(leaf -> hierarchy.path(leaf.fairnessKey()).forEach(node -> nodeKeys.add(node.key())));
        Map<String, HierarchyNodeState> states = hierarchyStateRepository.findByKeys(nodeKeys);

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, states, cms::estimateCount,
            adaptiveRpsController.getPenaltyFactor(), queueProperties.getVirtualTime().getQuantum(), freeSlots,
            maxPerClient);
        List<Task> locked = taskRepository.findAndLockQueuedHeads(plan.tasksPerLeaf());
        return new Selection(inPickOrder(locked, plan.pickOrder()), plan);
    }

    /** Persists node virtual time for the tasks of {@code selection} that were dispatched. */
    public void recordDispatch(Selection selection) {
        if (selection.plan() == null || selection.tasks().isEmpty()) {
            return;
        }
        HierarchicalSelector.Plan plan = selection.plan();
        Map<String, Double> charges = HierarchicalSelector.charges(asWeightedKeys(selection.tasks()), plan,
            hierarchy, queueProperties.getVirtualTime().getQuantum());
        charges.forEach((nodeKey, delta) ->
            hierarchyStateRepository.chargeVirtualTime(nodeKey, plan.nodeFloors().getOrDefault(nodeKey, 0.0), delta));
        plan.childrenFloors().forEach(hierarchyStateRepository::raiseChildrenFloor);
        selection.tasks().forEach(this::countDispatch);
    }

    /** Charges a sequentially dispatched task, which bypasses selection, to every node on its path. */
    public void recordSequentialDispatch(Task task) {
        if (!hierarchy.isEnabled()) {
            return;
        }
        List<HierarchyNode> path = hierarchy.path(task.getFairnessKey());
        Set<String> parents = new HashSet<>();
        path.forEach(node -> parents.add(node.parentKey()));
        Map<String, HierarchyNodeState> states = hierarchyStateRepository.findByKeys(parents);
        double quantum = queueProperties.getVirtualTime().getQuantum();
        for (HierarchyNode node : path) {
            HierarchyNodeState parent = states.get(node.parentKey());
            double floor = parent == null ? 0.0 : parent.childrenVirtualTime();
            hierarchyStateRepository.chargeVirtualTime(node.key(), floor,
                quantum / hierarchy.weight(node, task.effectiveWeight()));
        }
        countDispatch(task);
    }

    private void countDispatch(Task task) {
        for (HierarchyNode node : hierarchy.path(task.getFairnessKey())) {
            if (node.layer() < hierarchy.metricsDepth()) {
                performanceMonitor.recordHierarchicalDispatch(node.layerName(), node.key());
            }
        }
    }

    private static List<Map.Entry<String, Double>> asWeightedKeys(List<Task> tasks) {
        return tasks.stream().map(task -> Map.entry(task.getFairnessKey(), task.effectiveWeight())).toList();
    }

    /** Arranges locked tasks in the selector's pick order; picks whose task was locked elsewhere are skipped. */
    private static List<Task> inPickOrder(List<Task> locked, List<String> pickOrder) {
        Map<String, Deque<Task>> byKey = new HashMap<>();
        locked.forEach(task -> byKey.computeIfAbsent(task.getFairnessKey(), key -> new ArrayDeque<>()).add(task));
        List<Task> ordered = new ArrayList<>(locked.size());
        for (String fairnessKey : pickOrder) {
            Deque<Task> tasks = byKey.get(fairnessKey);
            if (tasks != null && !tasks.isEmpty()) {
                ordered.add(tasks.poll());
            }
        }
        return ordered;
    }

    /**
     * @param tasks locked tasks in dispatch order
     * @param plan the plan they were selected by; null when nothing was dispatchable
     */
    public record Selection(List<Task> tasks, HierarchicalSelector.@Nullable Plan plan) {
    }
}
