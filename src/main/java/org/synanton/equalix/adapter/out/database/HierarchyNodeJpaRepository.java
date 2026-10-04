package org.synanton.equalix.adapter.out.database;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.synanton.equalix.adapter.out.database.entity.HierarchyNodeEntity;

public interface HierarchyNodeJpaRepository extends JpaRepository<HierarchyNodeEntity, String> {

    @Modifying
    @Query(value = """
        INSERT INTO hierarchy_node (node_key, virtual_time, children_virtual_time, updated_at)
        VALUES (:key, :floor + :delta, 0, now())
        ON CONFLICT (node_key)
        DO UPDATE SET virtual_time = GREATEST(hierarchy_node.virtual_time, :floor) + :delta,
                      updated_at = now()
        """, nativeQuery = true)
    void chargeVirtualTime(@Param("key") String nodeKey, @Param("floor") double floor, @Param("delta") double delta);

    @Modifying
    @Query(value = """
        INSERT INTO hierarchy_node (node_key, virtual_time, children_virtual_time, updated_at)
        VALUES (:key, 0, :floor, now())
        ON CONFLICT (node_key)
        DO UPDATE SET children_virtual_time = GREATEST(hierarchy_node.children_virtual_time, :floor),
                      updated_at = now()
        """, nativeQuery = true)
    void raiseChildrenFloor(@Param("key") String nodeKey, @Param("floor") double floor);
}
