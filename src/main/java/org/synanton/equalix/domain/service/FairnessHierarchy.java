package org.synanton.equalix.domain.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.synanton.equalix.config.properties.HierarchicalProperties;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.model.HierarchyNode;

/**
 * Maps fairness keys onto the configured tenant tree (EQX-7).
 *
 * <p>With layers {@code [organization, department]} and separator {@code /}:
 * <ul>
 *   <li>{@code acme/sales} → organization {@code acme/} → department (leaf) {@code acme/sales};</li>
 *   <li>{@code acme/sales/bob} → the extra segment folds into the last layer: leaf {@code acme/sales/bob} under
 *       {@code acme/};</li>
 *   <li>{@code smallclub} → a leaf directly in the organization layer, competing with {@code acme/} at the
 *       root.</li>
 * </ul>
 * Internal node keys end with the separator, so they never collide with fairness keys; the root is {@code ""}.
 */
@Service
public class FairnessHierarchy {

    /** Key of the root node; also the CMS key that counts all in-flight tasks in hierarchical mode. */
    public static final String ROOT = "";

    /** Layer label used for metrics in flat mode. */
    public static final String FLAT_LAYER = "key";

    private static final String ROOT_LAYER = "root";

    private final boolean enabled;
    private final String separator;
    private final Pattern splitter;
    private final List<HierarchicalProperties.Layer> layers;
    private final Map<String, Double> weights;
    private final int metricsDepth;

    public FairnessHierarchy(QueueProperties queueProperties, HierarchicalProperties hierarchicalProperties) {
        this.enabled = queueProperties.getFairnessMode() == FairnessMode.HIERARCHICAL;
        this.separator = hierarchicalProperties.getSeparator();
        this.splitter = Pattern.compile(Pattern.quote(separator));
        this.layers = List.copyOf(hierarchicalProperties.getLayers());
        this.weights = Map.copyOf(hierarchicalProperties.getWeights());
        this.metricsDepth = hierarchicalProperties.getMetricsDepth();
        if (enabled && layers.isEmpty()) {
            throw new IllegalStateException("app.hierarchical.layers must not be empty in hierarchical mode");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Layers, from the root, whose nodes get dispatch counters. */
    public int metricsDepth() {
        return metricsDepth;
    }

    /** Nodes from the root's child down to the leaf; a single leaf node in flat mode. */
    public List<HierarchyNode> path(String fairnessKey) {
        if (!enabled) {
            return List.of(new HierarchyNode(fairnessKey, ROOT, 0, FLAT_LAYER, true));
        }
        String[] segments = splitter.split(fairnessKey, -1);
        int depth = Math.min(segments.length, layers.size());
        List<HierarchyNode> path = new ArrayList<>(depth);
        String parent = ROOT;
        StringBuilder prefix = new StringBuilder();
        for (int layer = 0; layer < depth - 1; layer++) {
            prefix.append(segments[layer]).append(separator);
            String key = prefix.toString();
            path.add(new HierarchyNode(key, parent, layer, layers.get(layer).getName(), false));
            parent = key;
        }
        path.add(new HierarchyNode(fairnessKey, parent, depth - 1, layers.get(depth - 1).getName(), true));
        return path;
    }

    /** Keys of the internal nodes above a fairness key, from the top; empty in flat mode. */
    public List<String> internalNodeKeys(String fairnessKey) {
        List<HierarchyNode> path = path(fairnessKey);
        return path.subList(0, path.size() - 1).stream().map(HierarchyNode::key).toList();
    }

    /** Weight override for the node's path, else the task weight for leaves or the layer default. */
    public double weight(HierarchyNode node, double leafTaskWeight) {
        String path = node.leaf() ? node.key() : node.key().substring(0, node.key().length() - separator.length());
        Double override = weights.get(path);
        if (override != null) {
            return override;
        }
        return node.leaf() ? leafTaskWeight : layers.get(node.layer()).getDefaultWeight();
    }

    /**
     * Adds the counts of every internal node and of the root ({@link #ROOT}) to per-fairness-key counts; returns
     * the input unchanged in flat mode.
     */
    public Map<String, Integer> withAncestors(Map<String, Integer> countsByFairnessKey) {
        if (!enabled) {
            return countsByFairnessKey;
        }
        Map<String, Integer> expanded = new HashMap<>();
        countsByFairnessKey.forEach((fairnessKey, count) -> {
            expanded.merge(fairnessKey, count, Integer::sum);
            internalNodeKeys(fairnessKey).forEach(node -> expanded.merge(node, count, Integer::sum));
            expanded.merge(ROOT, count, Integer::sum);
        });
        return expanded;
    }

    /** Layer name of a node key (fairness key, internal node key or {@link #ROOT}), for metrics. */
    public String layerOf(String nodeKey) {
        if (!enabled) {
            return FLAT_LAYER;
        }
        if (ROOT.equals(nodeKey)) {
            return ROOT_LAYER;
        }
        if (nodeKey.endsWith(separator)) {
            String path = nodeKey.substring(0, nodeKey.length() - separator.length());
            return layers.get(splitter.split(path, -1).length - 1).getName();
        }
        return path(nodeKey).getLast().layerName();
    }

    /** Returns why a fairness key cannot be placed in the tree, or null when it can (always null in flat mode). */
    @Nullable
    public String validationError(String fairnessKey) {
        if (!enabled) {
            return null;
        }
        for (String segment : splitter.split(fairnessKey, -1)) {
            if (segment.isEmpty()) {
                return "fairnessKey must not start or end with '" + separator + "' or contain empty segments";
            }
        }
        return null;
    }
}
