package org.synanton.equalix.domain.port.out;

import java.util.List;
import java.util.Map;
import org.synanton.equalix.domain.model.ClientCounts;

/** Outgoing port for the durable client in-flight count table. */
public interface ClientCountsRepositoryPort {

    void incrementInFlight(String fairnessKey);

    /** Decrements in-flight count, flooring at zero to avoid negative values. */
    void decrementInFlight(String fairnessKey);

    /** Inserts or updates the count for a fairness key; used by the watchdog during reconciliation. */
    void upsertCount(String fairnessKey, int count);

    List<ClientCounts> findAll();

    /** Returns the total in-flight count across all clients. */
    long totalInFlight();

    /** Returns current counts keyed by fairness key. Used to rebuild the CMS on startup and after watchdog. */
    Map<String, Integer> findAllAsMap();
}
