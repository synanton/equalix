package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.model.HierarchyNodeState;
import org.synanton.equalix.domain.model.QueuedLeaf;

class HierarchicalSelectorTest {

    private static final double QUANTUM = 1000.0;
    private static final ToLongFunction<String> NO_IN_FLIGHT = key -> 0L;

    private final FairnessHierarchy hierarchy = FairnessHierarchyTest.hierarchy(FairnessMode.HIERARCHICAL, Map.of());

    @Test
    void shouldAlternateBetweenEqualSiblingsRegardlessOfBacklog() {
        List<QueuedLeaf> leaves = List.of(leaf("acme/hot", 10_000), leaf("acme/cold", 3));

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, Map.of(), NO_IN_FLIGHT, 0,
            QUANTUM, 8, null);

        assertThat(plan.pickOrder()).containsExactly(
            "acme/cold", "acme/hot", "acme/cold", "acme/hot", "acme/cold", "acme/hot", "acme/hot", "acme/hot");
    }

    @Test
    void shouldSplitRootEquallyBetweenOrganizationAndSingleLeafTenant() {
        List<QueuedLeaf> leaves = new ArrayList<>();
        for (int department = 0; department < 10; department++) {
            leaves.add(leaf("big/dept" + department, 100));
        }
        leaves.add(leaf("small", 100));

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, Map.of(), NO_IN_FLIGHT, 0,
            QUANTUM, 20, null);

        assertThat(plan.tasksPerLeaf().get("small")).isEqualTo(10);
        assertThat(plan.tasksPerLeaf().entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("big/"))
            .mapToInt(Map.Entry::getValue).sum()).isEqualTo(10);
    }

    @Test
    void shouldServePromotedTasksFirst() {
        List<QueuedLeaf> leaves = List.of(leaf("acme/a", 5), new QueuedLeaf("acme/b", 5, 2, 1.0, 0));

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, Map.of(), NO_IN_FLIGHT, 0,
            QUANTUM, 3, null);

        assertThat(plan.pickOrder()).startsWith("acme/b", "acme/b");
    }

    @Test
    void shouldCapLeafAtRemainingQuota() {
        List<QueuedLeaf> leaves = List.of(new QueuedLeaf("acme/a", 10, 0, 1.0, 8), leaf("acme/b", 1));

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, Map.of(), NO_IN_FLIGHT, 0,
            QUANTUM, 10, 10);

        assertThat(plan.tasksPerLeaf()).isEqualTo(Map.of("acme/a", 2, "acme/b", 1));
    }

    @Test
    void shouldPreferNodeWithLessInFlightPressure() {
        List<QueuedLeaf> leaves = List.of(leaf("acme/busy", 10), leaf("acme/idle", 10));
        ToLongFunction<String> inFlight = key -> key.equals("acme/busy") ? 5L : 0L;

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, Map.of(), inFlight, 400,
            QUANTUM, 2, null);

        // Pressure 5 * 400 = 2000 units, two tasks' worth: idle gets both of the next two slots.
        assertThat(plan.pickOrder()).containsExactly("acme/idle", "acme/idle");
    }

    @Test
    void shouldRestartIdleChildAtParentFloorInsteadOfStaleRuntime() {
        Map<String, HierarchyNodeState> states = Map.of(
            "acme/", new HierarchyNodeState("acme/", 0, 50_000),
            "acme/returning", new HierarchyNodeState("acme/returning", 1_000, 0),
            "acme/busy", new HierarchyNodeState("acme/busy", 50_000, 0));

        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(
            List.of(leaf("acme/busy", 10), leaf("acme/returning", 10)), hierarchy, states, NO_IN_FLIGHT, 0,
            QUANTUM, 4, null);

        assertThat(plan.pickOrder()).containsExactly("acme/busy", "acme/returning", "acme/busy", "acme/returning");
        assertThat(plan.nodeFloors().get("acme/returning")).isEqualTo(50_000.0);
    }

    @Test
    void shouldChargeEveryNodeOnDispatchedPaths() {
        HierarchicalSelector.Plan plan = HierarchicalSelector.plan(List.of(leaf("acme/a", 2)), hierarchy, Map.of(),
            NO_IN_FLIGHT, 0, QUANTUM, 2, null);

        Map<String, Double> charges = HierarchicalSelector.charges(
            List.of(Map.entry("acme/a", 1.0), Map.entry("acme/a", 1.0)), plan, hierarchy, QUANTUM);

        assertThat(charges).isEqualTo(Map.of("acme/", 2_000.0, "acme/a", 2_000.0));
    }

    @Test
    void shouldIsolateSiblingsAndBoundOrganizationOverManyTicks() {
        // DoD: a hot department cannot starve its sibling, and a 10-department organization is bounded by its own
        // weight against a single-leaf tenant.
        Map<String, Integer> backlog = new LinkedHashMap<>();
        backlog.put("big/hot", 1_000_000);
        for (int department = 1; department < 10; department++) {
            backlog.put("big/dept" + department, 1_000_000);
        }
        backlog.put("small", 1_000_000);

        Map<String, Integer> dispatched = simulate(hierarchy, backlog, 10_000, 20);

        double big = dispatched.entrySet().stream().filter(entry -> entry.getKey().startsWith("big/"))
            .mapToInt(Map.Entry::getValue).sum() / 10_000.0;
        assertThat(big).isCloseTo(0.5, within(0.001));
        assertThat(dispatched.get("small") / 10_000.0).isCloseTo(0.5, within(0.001));
        assertThat(dispatched.get("big/hot") / 10_000.0).isCloseTo(0.05, within(0.001));
    }

    @Test
    void shouldGiveCompositeKeysOrganizationTimesDepartmentsShareInFlatMode() {
        // The flat-mode problem EQX-7 addresses: every composite key is an independent tenant at the root.
        FairnessHierarchy flat = FairnessHierarchyTest.hierarchy(FairnessMode.FLAT, Map.of());
        Map<String, Integer> backlog = new LinkedHashMap<>();
        for (int department = 0; department < 10; department++) {
            backlog.put("big/dept" + department, 1_000_000);
        }
        backlog.put("small", 1_000_000);

        Map<String, Integer> dispatched = simulate(flat, backlog, 11_000, 20);

        assertThat(dispatched.get("small") / 11_000.0).isCloseTo(1.0 / 11, within(0.001));
    }

    @Test
    void shouldApplyOrganizationAndDepartmentWeights() {
        FairnessHierarchy weighted = FairnessHierarchyTest.hierarchy(FairnessMode.HIERARCHICAL,
            Map.of("big", 3.0, "big/a", 3.0));
        Map<String, Integer> backlog = new LinkedHashMap<>();
        backlog.put("big/a", 1_000_000);
        backlog.put("big/b", 1_000_000);
        backlog.put("small", 1_000_000);

        Map<String, Integer> dispatched = simulate(weighted, backlog, 8_000, 20);

        // Root: big 3 : small 1. Inside big: a 3 : b 1.
        assertThat(dispatched.get("small") / 8_000.0).isCloseTo(0.25, within(0.001));
        assertThat(dispatched.get("big/a") / 8_000.0).isCloseTo(0.5625, within(0.001));
        assertThat(dispatched.get("big/b") / 8_000.0).isCloseTo(0.1875, within(0.001));
    }

    @Test
    void shouldNotLetReturningDepartmentBurstAfterIdling() {
        Map<String, HierarchyNodeState> states = new HashMap<>();
        Map<String, Integer> busyOnly = new LinkedHashMap<>(Map.of("acme/busy", 1_000_000));
        simulate(hierarchy, busyOnly, 1_000, 20, states);

        Map<String, Integer> both = new LinkedHashMap<>();
        both.put("acme/busy", 1_000_000);
        both.put("acme/returning", 1_000_000);
        Map<String, Integer> dispatched = simulate(hierarchy, both, 100, 20, states);

        assertThat(dispatched.get("acme/returning")).isBetween(49, 51);
    }

    private static Map<String, Integer> simulate(FairnessHierarchy hierarchy, Map<String, Integer> backlog,
        int dispatches, int slotsPerTick) {
        return simulate(hierarchy, backlog, dispatches, slotsPerTick, new HashMap<>());
    }

    /** Runs ticks of plan → dispatch → persist with the same update rules as the database adapter. */
    private static Map<String, Integer> simulate(FairnessHierarchy hierarchy, Map<String, Integer> backlog,
        int dispatches, int slotsPerTick, Map<String, HierarchyNodeState> states) {
        Map<String, Integer> remaining = new LinkedHashMap<>(backlog);
        Map<String, Integer> dispatched = new HashMap<>();
        int total = 0;
        while (total < dispatches) {
            List<QueuedLeaf> leaves = remaining.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> leaf(entry.getKey(), entry.getValue()))
                .toList();
            int slots = Math.min(slotsPerTick, dispatches - total);
            HierarchicalSelector.Plan plan = HierarchicalSelector.plan(leaves, hierarchy, states, NO_IN_FLIGHT, 0,
                QUANTUM, slots, null);
            List<Map.Entry<String, Double>> tasks = plan.pickOrder().stream()
                .map(key -> Map.entry(key, 1.0))
                .toList();
            persist(states, plan, HierarchicalSelector.charges(tasks, plan, hierarchy, QUANTUM));
            for (String key : plan.pickOrder()) {
                remaining.merge(key, -1, Integer::sum);
                dispatched.merge(key, 1, Integer::sum);
            }
            total += plan.pickOrder().size();
        }
        return dispatched;
    }

    private static void persist(Map<String, HierarchyNodeState> states, HierarchicalSelector.Plan plan,
        Map<String, Double> charges) {
        charges.forEach((key, delta) -> {
            HierarchyNodeState current = states.get(key);
            double floor = plan.nodeFloors().getOrDefault(key, 0.0);
            double base = current == null ? floor : Math.max(current.virtualTime(), floor);
            states.put(key, new HierarchyNodeState(key, base + delta,
                current == null ? 0.0 : current.childrenVirtualTime()));
        });
        plan.childrenFloors().forEach((key, floor) -> {
            HierarchyNodeState current = states.get(key);
            double virtualTime = current == null ? 0.0 : current.virtualTime();
            double childrenFloor = current == null ? floor : Math.max(current.childrenVirtualTime(), floor);
            states.put(key, new HierarchyNodeState(key, virtualTime, childrenFloor));
        });
    }

    private static QueuedLeaf leaf(String key, int queued) {
        return new QueuedLeaf(key, queued, 0, 1.0, 0);
    }
}
