package org.synanton.equalix.domain.model;

/**
 * Persistent scheduling state of one hierarchy node (EQX-7).
 *
 * @param key node key (see {@link HierarchyNode#key()}); {@code ""} for the root
 * @param virtualTime service the node has received, in its parent's virtual time (CFS vruntime)
 * @param childrenVirtualTime floor for the node's children: a child that was idle restarts here, so it cannot
 *     bank credit (CFS min_vruntime)
 */
public record HierarchyNodeState(String key, double virtualTime, double childrenVirtualTime) {
}
