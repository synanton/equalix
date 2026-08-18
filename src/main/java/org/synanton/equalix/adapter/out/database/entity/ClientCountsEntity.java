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
@Table(name = "client_counts")
public class ClientCountsEntity {

    @Id
    @Column(name = "fairness_key")
    private String fairnessKey;

    @Column(name = "in_flight_count", nullable = false)
    private int inFlightCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
