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
@Table(name = "client_virtual_time")
public class ClientVirtualTimeEntity {

    @Id
    @Column(name = "fairness_key")
    private String fairnessKey;

    @Column(name = "virtual_time", nullable = false)
    private double virtualTime;

    @Column(name = "virtual_finish", nullable = false)
    private double virtualFinish;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
