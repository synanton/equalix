package org.synanton.equalix.domain.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.config.properties.WatchdogProperties;
import org.synanton.equalix.domain.model.CmsDriftReport;
import org.synanton.equalix.domain.model.FairnessMode;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class WatchdogServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:05:00Z");

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private CmsErrorRecorder cmsErrorRecorder;
    @Mock
    private PerformanceMonitorPort performanceMonitor;

    private WatchdogService watchdogService;

    @BeforeEach
    void setUp() {
        WatchdogProperties props = new WatchdogProperties();
        props.setDriftMetricMaxKeys(10);
        watchdogService = new WatchdogService(taskRepository, clientCounts, cms, cmsErrorRecorder,
            performanceMonitor, props, FairnessHierarchyTest.hierarchy(FairnessMode.FLAT, Map.of()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReconcileClientCountsFromTaskAggregates() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of("clientA", 3));
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("clientA", 5));
        when(cmsErrorRecorder.measureDrift(Map.of("clientA", 3), Set.of("clientA")))
            .thenReturn(Map.of("clientA", 0L));

        watchdogService.reconcile();

        verify(clientCounts).upsertCount("clientA", 3);
        verify(cms).rebuild(Map.of("clientA", 3));
    }

    @Test
    void shouldZeroStoredCountsWhenNoInFlightTasksRemain() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of());
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("clientA", 2));
        when(cmsErrorRecorder.measureDrift(Map.of(), Set.of("clientA"))).thenReturn(Map.of("clientA", 0L));

        watchdogService.reconcile();

        verify(clientCounts).upsertCount("clientA", 0);
        verify(cms).rebuild(Map.of());
    }

    @Test
    void shouldNotUpsertWhenCountsAlreadyMatchTasks() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of("clientA", 3));
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("clientA", 3));
        when(cmsErrorRecorder.measureDrift(Map.of("clientA", 3), Set.of("clientA")))
            .thenReturn(Map.of("clientA", 0L));

        watchdogService.reconcile();

        verify(clientCounts, never()).upsertCount(anyString(), anyInt());
        verify(cms).rebuild(Map.of("clientA", 3));
    }

    @Test
    void shouldPublishDriftOfEveryTrackedKeyBeforeRebuildingTheSketch() {
        Map<String, Integer> inFlight = Map.of("busy", 3, "under", 4);
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(inFlight);
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("busy", 3, "phantom", 0));
        when(cmsErrorRecorder.measureDrift(inFlight, Set.of("busy", "under", "phantom")))
            .thenReturn(Map.of("busy", 0L, "under", -1L, "phantom", 2L));

        watchdogService.reconcile();

        InOrder order = inOrder(performanceMonitor, cms);
        order.verify(performanceMonitor).publishCmsDrift(new CmsDriftReport(NOW, 3, 2, 2, -1, 3,
            Map.of("phantom", 2L, "under", -1L), Map.of("phantom", "key", "under", "key"), Map.of("key", 3L)));
        order.verify(cms).rebuild(inFlight);
    }

    @Test
    void shouldMeasureDriftOfInternalNodesAndRootInHierarchicalMode() {
        WatchdogProperties props = new WatchdogProperties();
        props.setDriftMetricMaxKeys(10);
        WatchdogService hierarchical = new WatchdogService(taskRepository, clientCounts, cms, cmsErrorRecorder,
            performanceMonitor, props, FairnessHierarchyTest.hierarchy(FairnessMode.HIERARCHICAL, Map.of()),
            Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, Integer> inFlight = Map.of("acme/sales", 2, "acme/it", 1);
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(inFlight);
        when(clientCounts.findAllAsMap()).thenReturn(inFlight);
        when(cmsErrorRecorder.measureDrift(Map.of("acme/sales", 2, "acme/it", 1, "acme/", 3, "", 3),
            Set.of("acme/sales", "acme/it", "acme/", "")))
            .thenReturn(Map.of("acme/sales", 0L, "acme/it", 0L, "acme/", 1L, "", 1L));

        hierarchical.reconcile();

        verify(performanceMonitor).publishCmsDrift(new CmsDriftReport(NOW, 4, 2, 1, 0, 2,
            Map.of("acme/", 1L, "", 1L), Map.of("acme/", "organization", "", "root"),
            Map.of("organization", 1L, "department", 0L, "root", 1L)));
        verify(cms).rebuild(inFlight);
    }

    @Test
    void shouldWarmUpSketchFromInFlightTasksWithoutPublishingDrift() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of("clientA", 2));

        watchdogService.warmUpCms();

        verify(cms).rebuild(Map.of("clientA", 2));
        verifyNoInteractions(performanceMonitor, cmsErrorRecorder, clientCounts);
    }
}
