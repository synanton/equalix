package org.synanton.equalix.adapter.out.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.experimental.Accessors;

/** Single-row table holding the system virtual time V. */
@Data
@Accessors(chain = true)
@Entity
@Table(name = "scheduler_virtual_clock")
public class SchedulerVirtualClockEntity {

    @Id
    private short id;

    @Column(name = "virtual_time", nullable = false)
    private double virtualTime;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
