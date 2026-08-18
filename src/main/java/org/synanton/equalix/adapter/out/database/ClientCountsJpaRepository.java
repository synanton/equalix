package org.synanton.equalix.adapter.out.database;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.synanton.equalix.adapter.out.database.entity.ClientCountsEntity;

public interface ClientCountsJpaRepository extends JpaRepository<ClientCountsEntity, String> {

    @Modifying
    @Query(value = """
        INSERT INTO client_counts (fairness_key, in_flight_count, updated_at)
        VALUES (:key, 1, now())
        ON CONFLICT (fairness_key)
        DO UPDATE SET in_flight_count = GREATEST(0, client_counts.in_flight_count + 1), updated_at = now()
        """, nativeQuery = true)
    void incrementInFlight(@Param("key") String fairnessKey);

    @Modifying
    @Query(value = """
        UPDATE client_counts
        SET in_flight_count = GREATEST(0, in_flight_count - 1), updated_at = now()
        WHERE fairness_key = :key
        """, nativeQuery = true)
    void decrementInFlight(@Param("key") String fairnessKey);

    @Modifying
    @Query(value = """
        INSERT INTO client_counts (fairness_key, in_flight_count, updated_at)
        VALUES (:key, :count, now())
        ON CONFLICT (fairness_key)
        DO UPDATE SET in_flight_count = :count, updated_at = now()
        """, nativeQuery = true)
    void upsertCount(@Param("key") String fairnessKey, @Param("count") int count);

    @Query("SELECT COALESCE(SUM(c.inFlightCount), 0) FROM ClientCountsEntity c")
    long totalInFlight();
}
