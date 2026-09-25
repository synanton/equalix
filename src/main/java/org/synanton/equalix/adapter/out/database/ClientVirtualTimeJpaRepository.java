package org.synanton.equalix.adapter.out.database;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.synanton.equalix.adapter.out.database.entity.ClientVirtualTimeEntity;

public interface ClientVirtualTimeJpaRepository extends JpaRepository<ClientVirtualTimeEntity, String> {

    /** Atomic read-modify-write so concurrent reservations for one key never receive the same tag. */
    @Query(value = """
        INSERT INTO client_virtual_time (fairness_key, virtual_time, virtual_finish, updated_at)
        VALUES (:key, :systemVirtualTime, :systemVirtualTime + :increment, now())
        ON CONFLICT (fairness_key)
        DO UPDATE SET virtual_finish = GREATEST(client_virtual_time.virtual_finish, :systemVirtualTime) + :increment,
                      updated_at = now()
        RETURNING virtual_finish
        """, nativeQuery = true)
    double reserveFinishTag(
        @Param("key") String fairnessKey,
        @Param("systemVirtualTime") double systemVirtualTime,
        @Param("increment") double increment);

    @Modifying
    @Query(value = """
        INSERT INTO client_virtual_time (fairness_key, virtual_time, virtual_finish, updated_at)
        VALUES (:key, :finishTag, :finishTag, now())
        ON CONFLICT (fairness_key)
        DO UPDATE SET virtual_time = GREATEST(client_virtual_time.virtual_time, :finishTag),
                      virtual_finish = GREATEST(client_virtual_time.virtual_finish, :finishTag),
                      updated_at = now()
        """, nativeQuery = true)
    void advanceVirtualTime(@Param("key") String fairnessKey, @Param("finishTag") double finishTag);
}
