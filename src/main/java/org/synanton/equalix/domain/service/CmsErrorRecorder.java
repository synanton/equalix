package org.synanton.equalix.domain.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.domain.model.CmsErrorStatistics;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * Samples the CMS estimation error {@code e_k = F̂_k - F_k} (invariants §16) against the authoritative in-flight
 * count from the task table, and publishes every sample as a metric.
 *
 * <p>Keys are those with in-flight tasks plus those with a {@code client_counts} row, so phantom estimates for
 * keys that are no longer in flight are sampled too. Under concurrent dispatch the task-table snapshot and the
 * CMS reads are not atomic; samples then include updates that are in transit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CmsErrorRecorder {

    private final TaskRepositoryPort taskRepository;
    private final ClientCountsRepositoryPort clientCounts;
    private final CMSProviderPort cms;
    private final PerformanceMonitorPort performanceMonitor;

    @Transactional(readOnly = true)
    public CmsErrorStatistics sample() {
        Map<String, Integer> actual = taskRepository.countInFlightByFairnessKey();
        Set<String> fairnessKeys = new HashSet<>(actual.keySet());
        fairnessKeys.addAll(clientCounts.findAllAsMap().keySet());

        CmsErrorStatistics statistics = new CmsErrorStatistics();
        for (String fairnessKey : fairnessKeys) {
            long error = cms.estimateCount(fairnessKey) - actual.getOrDefault(fairnessKey, 0);
            statistics.record(error);
            performanceMonitor.recordCmsEstimationError(error);
        }
        log.debug("CMS estimation error sample: {}", statistics);
        return statistics;
    }
}
