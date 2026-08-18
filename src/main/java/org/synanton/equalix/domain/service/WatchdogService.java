package org.synanton.equalix.domain.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * Periodically reconciles client_counts and the CMS against in-flight task rows.
 * Two-phase: repair client_counts from DISPATCHED/COMMITTED aggregates, then rebuild CMS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchdogService {

    private final TaskRepositoryPort taskRepository;
    private final ClientCountsRepositoryPort clientCounts;
    private final CMSProviderPort cms;

    @Transactional
    public void reconcile() {
        log.info("Watchdog reconciliation started");

        Map<String, Integer> actual = taskRepository.countInFlightByFairnessKey();
        Map<String, Integer> stored = clientCounts.findAllAsMap();

        Set<String> keys = new HashSet<>();
        keys.addAll(actual.keySet());
        keys.addAll(stored.keySet());

        int updates = 0;
        for (String fairnessKey : keys) {
            int expectedCount = actual.getOrDefault(fairnessKey, 0);
            int storedCount = stored.getOrDefault(fairnessKey, 0);
            if (storedCount != expectedCount) {
                clientCounts.upsertCount(fairnessKey, expectedCount);
                updates++;
            }
        }

        cms.rebuild(actual);

        log.info("Watchdog reconciliation complete: {} counts corrected, CMS rebuilt from {} entries",
            updates, actual.size());
    }
}
