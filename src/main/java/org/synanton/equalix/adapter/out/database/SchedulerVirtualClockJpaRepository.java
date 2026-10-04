package org.synanton.equalix.adapter.out.database;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.synanton.equalix.adapter.out.database.entity.SchedulerVirtualClockEntity;

public interface SchedulerVirtualClockJpaRepository extends JpaRepository<SchedulerVirtualClockEntity, Short> {

    @Query(value = "SELECT COALESCE(MAX(virtual_time), 0) FROM scheduler_virtual_clock WHERE id = 1",
        nativeQuery = true)
    double findSystemVirtualTime();

    @Modifying
    @Query(value = """
        INSERT INTO scheduler_virtual_clock (id, virtual_time, updated_at)
        VALUES (1, :finishTag, now())
        ON CONFLICT (id)
        DO UPDATE SET virtual_time = GREATEST(scheduler_virtual_clock.virtual_time, :finishTag),
                      updated_at = now()
        """, nativeQuery = true)
    void advance(@Param("finishTag") double finishTag);
}
