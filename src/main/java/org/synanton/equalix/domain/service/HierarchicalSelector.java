package org.synanton.equalix.domain.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;
import org.synanton.equalix.domain.model.HierarchyNode;
import org.synanton.equalix.domain.model.HierarchyNodeState;
import org.synanton.equalix.domain.model.QueuedLeaf;

/**
 * Hierarchical fair selection in the style of CFS group scheduling (EQX-7). Pure: no I/O.
 *
 * <p>Every node has a virtual runtime {@code vt} in its parent's virtual time. For each slot, the selector walks
 * down from the root and at each node picks the backlogged child with the smallest
 * {@code vt + quantum / w + p · F̂ / w}: its virtual finish time after one more task, plus its in-flight pressure.
 * The chosen leaf and every node above it are then charged {@code quantum / w}. So each child receives a
 * {@code w / Σw} share of its parent's service, among the siblings that are backlogged, at every layer.
 *
 * <p>Each parent keeps a floor, the smallest {@code vt} among its backlogged children. A child that was idle
 * restarts at the floor rather than its stale runtime, so idleness does not bank credit; backlogged children are
 * never below it. Tasks promoted by the starvation deadline are served first, but are charged normally.
 */
public final class HierarchicalSelector {

    private HierarchicalSelector() {
    }

    /**
     * @param leaves dispatchable backlog per fairness key
     * @param states persisted state of the nodes on the leaves' paths and of their parents; missing nodes are new
     * @param inFlightEstimate approximate in-flight count per node key (CMS with ancestor counts)
     * @param penaltyFactor pressure coefficient p(t)
     * @param quantum virtual-time cost of one task at weight 1
     * @param freeSlots tasks to select
     * @param maxPerClient per-leaf hard quota, or null
     */
    public static Plan plan(List<QueuedLeaf> leaves, FairnessHierarchy hierarchy,
        Map<String, HierarchyNodeState> states, ToLongFunction<String> inFlightEstimate, double penaltyFactor,
        double quantum, int freeSlots, @Nullable Integer maxPerClient) {

        Node root = new Node(FairnessHierarchy.ROOT, 1.0, false);
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(root.key, root);
        List<Node> promotedLeaves = new ArrayList<>();
        for (QueuedLeaf leaf : leaves) {
            int capacity = maxPerClient == null
                ? leaf.queued()
                : Math.min(leaf.queued(), Math.max(0, maxPerClient - leaf.inFlight()));
            if (capacity <= 0) {
                continue;
            }
            Node parent = root;
            for (HierarchyNode pathNode : hierarchy.path(leaf.fairnessKey())) {
                Node current = nodes.get(pathNode.key());
                if (current == null) {
                    current = new Node(pathNode.key(), hierarchy.weight(pathNode, leaf.maxWeight()), pathNode.leaf());
                    HierarchyNodeState state = states.get(pathNode.key());
                    current.virtualTime = state == null ? Double.NaN : state.virtualTime();
                    current.parent = parent;
                    parent.children.add(current);
                    nodes.put(current.key, current);
                }
                parent = current;
            }
            parent.remaining = capacity;
            parent.promoted = Math.min(leaf.promoted(), capacity);
            for (Node node = parent; node != null; node = node.parent) {
                node.activeLeaves++;
            }
            if (parent.promoted > 0) {
                promotedLeaves.add(parent);
            }
        }

        Map<String, Double> nodeFloors = new HashMap<>();
        applyFloors(root, states, nodeFloors);
        nodes.values().forEach(node -> node.pressure =
            node == root ? 0.0 : penaltyFactor * inFlightEstimate.applyAsLong(node.key) / node.weight);

        List<String> pickOrder = new ArrayList<>();
        promotedLeaves.sort(Comparator.comparing(node -> node.key));
        for (Node leaf : promotedLeaves) {
            while (leaf.promoted > 0 && pickOrder.size() < freeSlots) {
                leaf.promoted--;
                charge(leaf, quantum, pickOrder);
            }
        }
        while (pickOrder.size() < freeSlots && root.isBacklogged()) {
            Node node = root;
            while (!node.leaf) {
                node = node.bestChild(quantum);
            }
            charge(node, quantum, pickOrder);
        }

        // Floors are taken after this tick's charges: a child that returns next tick then starts level with its
        // busiest sibling, not one tick's worth of service behind it.
        Map<String, Double> childrenFloors = new HashMap<>();
        raiseChildrenFloors(root, states, childrenFloors);

        Map<String, Integer> tasksPerLeaf = new LinkedHashMap<>();
        pickOrder.forEach(key -> tasksPerLeaf.merge(key, 1, Integer::sum));
        Map<String, Double> weights = new HashMap<>();
        nodes.values().forEach(node -> weights.put(node.key, node.weight));
        return new Plan(pickOrder, tasksPerLeaf, weights, nodeFloors, childrenFloors);
    }

    /**
     * Virtual-time charge per node for tasks that were actually dispatched: {@code quantum / w} for every node on
     * each task's path (the root excluded). Weights come from the plan; nodes it does not know use the hierarchy
     * with {@code taskWeight}.
     *
     * @param dispatched fairness key and task weight of every dispatched task
     */
    public static Map<String, Double> charges(List<Map.Entry<String, Double>> dispatched, Plan plan,
        FairnessHierarchy hierarchy, double quantum) {
        Map<String, Double> charges = new LinkedHashMap<>();
        for (Map.Entry<String, Double> task : dispatched) {
            for (HierarchyNode node : hierarchy.path(task.getKey())) {
                double weight = plan.nodeWeights().containsKey(node.key())
                    ? plan.nodeWeights().get(node.key())
                    : hierarchy.weight(node, task.getValue());
                charges.merge(node.key(), quantum / weight, Double::sum);
            }
        }
        return charges;
    }

    private static void applyFloors(Node parent, Map<String, HierarchyNodeState> states,
        Map<String, Double> nodeFloors) {
        double floor = storedChildrenFloor(parent, states);
        for (Node child : parent.children) {
            child.virtualTime = Double.isNaN(child.virtualTime) ? floor : Math.max(child.virtualTime, floor);
            nodeFloors.put(child.key, floor);
            applyFloors(child, states, nodeFloors);
        }
    }

    private static void raiseChildrenFloors(Node parent, Map<String, HierarchyNodeState> states,
        Map<String, Double> childrenFloors) {
        if (parent.children.isEmpty()) {
            return;
        }
        double lowestChild = Double.POSITIVE_INFINITY;
        for (Node child : parent.children) {
            lowestChild = Math.min(lowestChild, child.virtualTime);
            raiseChildrenFloors(child, states, childrenFloors);
        }
        childrenFloors.put(parent.key, Math.max(storedChildrenFloor(parent, states), lowestChild));
    }

    private static double storedChildrenFloor(Node parent, Map<String, HierarchyNodeState> states) {
        HierarchyNodeState state = states.get(parent.key);
        return state == null ? 0.0 : state.childrenVirtualTime();
    }

    private static void charge(Node leaf, double quantum, List<String> pickOrder) {
        leaf.remaining--;
        boolean exhausted = leaf.remaining == 0;
        for (Node node = leaf; node != null; node = node.parent) {
            if (node.parent != null) {
                node.virtualTime += quantum / node.weight;
            }
            if (exhausted) {
                node.activeLeaves--;
            }
        }
        pickOrder.add(leaf.key);
    }

    /**
     * @param pickOrder leaf key of every selected slot, in dispatch order
     * @param tasksPerLeaf number of slots per leaf
     * @param nodeWeights weight used for every node on the selected paths
     * @param nodeFloors floor applied to each node (its parent's children floor) before charging
     * @param childrenFloors new children floor per parent
     */
    public record Plan(List<String> pickOrder, Map<String, Integer> tasksPerLeaf, Map<String, Double> nodeWeights,
                       Map<String, Double> nodeFloors, Map<String, Double> childrenFloors) {
    }

    private static final class Node {

        private final String key;
        private final double weight;
        private final boolean leaf;
        private final List<Node> children = new ArrayList<>();
        @Nullable
        private Node parent;
        private double virtualTime;
        private double pressure;
        private int remaining;
        private int promoted;
        /** Leaves in this subtree that still have tasks to select. */
        private int activeLeaves;

        Node(String key, double weight, boolean leaf) {
            this.key = key;
            this.weight = weight;
            this.leaf = leaf;
        }

        boolean isBacklogged() {
            return activeLeaves > 0;
        }

        Node bestChild(double quantum) {
            Node best = null;
            double bestKey = Double.POSITIVE_INFINITY;
            for (Node child : children) {
                if (!child.isBacklogged()) {
                    continue;
                }
                double selectionKey = child.virtualTime + quantum / child.weight + child.pressure;
                if (best == null || selectionKey < bestKey
                    || (selectionKey == bestKey && child.key.compareTo(best.key) < 0)) {
                    best = child;
                    bestKey = selectionKey;
                }
            }
            if (best == null) {
                throw new IllegalStateException("No backlogged child under " + key);
            }
            return best;
        }
    }
}
