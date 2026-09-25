package org.synanton.equalix.domain.model;

/**
 * One node on a fairness key's path through the hierarchy (EQX-7).
 *
 * @param key node key: the fairness key itself for a leaf, the path plus a trailing separator for an internal
 *     node (e.g. {@code acme/}), so internal nodes never collide with fairness keys
 * @param parentKey key of the parent node; {@code ""} for children of the root
 * @param layer layer index from the root, 0-based
 * @param layerName configured name of the layer
 * @param leaf whether the node is the fairness key itself
 */
public record HierarchyNode(String key, String parentKey, int layer, String layerName, boolean leaf) {
}
