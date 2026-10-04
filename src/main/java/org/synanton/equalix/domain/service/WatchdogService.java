package org.synanton.equalix.domain.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.synanton.equalix.config.properties.WatchdogProperties;
import org.synanton.equalix.domain.model.CmsDriftReport;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

/**
 * Periodically reconciles client_counts and the CMS against in-flight task rows.
 * Two-phase: repair client_counts from DISPATCHED/COMMITTED aggregates, then rebuild CMS.
 * Before the rebuild, the CMS drift {@code F̂_k - F_k} is measured and published (EQX-5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchdogService {

    private final TaskRepositoryPort taskRepository;
    private final ClientCountsRepositoryPort clientCounts;
    private final CMSProviderPort cms;
    private final CmsErrorRecorder cmsErrorRecorder;
    private final PerformanceMonitorPort performanceMonitor;
    private final WatchdogProperties watchdogProperties;
    private final FairnessHierarchy hierarchy;
    private final Clock clock;

    /**
     * Rebuilds the CMS from in-flight tasks without publishing drift; used at startup, when an empty sketch is
     * expected and would otherwise read as an underestimate for every key.
     */
    @Transactional(readOnly = true)
    public void warmUpCms() {
        Map<String, Integer> actual = taskRepository.countInFlightByFairnessKey();
        cms.rebuild(actual);
        log.info("CMS warmed up from {} keys with in-flight tasks", actual.size());
    }

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

        // Drift must be measured against the sketch as the scheduler has been using it, i.e. before the rebuild.
        // In hierarchical mode internal nodes and the root are measured too; their true count is their leaves' sum.
        Set<String> driftKeys = new HashSet<>(keys);
        if (hierarchy.isEnabled()) {
            keys.forEach(fairnessKey -> driftKeys.addAll(hierarchy.internalNodeKeys(fairnessKey)));
            driftKeys.add(FairnessHierarchy.ROOT);
        }
        CmsDriftReport drift = CmsDriftReport.of(
            cmsErrorRecorder.measureDrift(hierarchy.withAncestors(actual), driftKeys),
            watchdogProperties.getDriftMetricMaxKeys(),
            Instant.now(clock),
            hierarchy::layerOf);
        performanceMonitor.publishCmsDrift(drift);

        cms.rebuild(actual);

        log.info("Watchdog reconciliation complete: {} counts corrected, CMS rebuilt from {} entries; "
                + "CMS drift: {} of {} keys drifting, max={} min={} total|drift|={}",
            updates, actual.size(), drift.keysDrifting(), drift.keysSampled(), drift.maxDrift(), drift.minDrift(),
            drift.absoluteDriftTotal());
    }
}
