package org.synanton.equalix.domain.port.out;

import java.util.Collection;
import java.util.Map;
import org.synanton.equalix.domain.model.HierarchyNodeState;

/** Durable per-node state for hierarchical scheduling (EQX-7). Updates are atomic and monotonic. */
public interface HierarchyStateRepositoryPort {

    /** States of the given node keys that exist; missing keys are new nodes. */
    Map<String, HierarchyNodeState> findByKeys(Collection<String> nodeKeys);

    /** {@code virtual_time = max(virtual_time, floor) + delta}, creating the node when absent. */
    void chargeVirtualTime(String nodeKey, double floor, double delta);

    /** {@code children_virtual_time = max(children_virtual_time, floor)}, creating the node when absent. */
    void raiseChildrenFloor(String nodeKey, double floor);
}
