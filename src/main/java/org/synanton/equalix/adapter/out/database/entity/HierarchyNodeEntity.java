package org.synanton.equalix.adapter.out.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "hierarchy_node")
public class HierarchyNodeEntity {

    @Id
    @Column(name = "node_key")
    private String nodeKey;

    @Column(name = "virtual_time", nullable = false)
    private double virtualTime;

    @Column(name = "children_virtual_time", nullable = false)
    private double childrenVirtualTime;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
