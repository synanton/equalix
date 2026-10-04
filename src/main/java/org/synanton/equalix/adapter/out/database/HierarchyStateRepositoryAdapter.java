package org.synanton.equalix.adapter.out.database;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.model.HierarchyNodeState;
import org.synanton.equalix.domain.port.out.HierarchyStateRepositoryPort;

@Component
@RequiredArgsConstructor
public class HierarchyStateRepositoryAdapter implements HierarchyStateRepositoryPort {

    private final HierarchyNodeJpaRepository jpaRepository;

    @Override
    public Map<String, HierarchyNodeState> findByKeys(Collection<String> nodeKeys) {
        return jpaRepository.findAllById(nodeKeys).stream()
            .collect(Collectors.toMap(
                entity -> entity.getNodeKey(),
                entity -> new HierarchyNodeState(
                    entity.getNodeKey(), entity.getVirtualTime(), entity.getChildrenVirtualTime())));
    }

    @Override
    public void chargeVirtualTime(String nodeKey, double floor, double delta) {
        jpaRepository.chargeVirtualTime(nodeKey, floor, delta);
    }

    @Override
    public void raiseChildrenFloor(String nodeKey, double floor) {
        jpaRepository.raiseChildrenFloor(nodeKey, floor);
    }
}
