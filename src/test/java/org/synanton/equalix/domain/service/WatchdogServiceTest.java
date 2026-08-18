package org.synanton.equalix.domain.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class WatchdogServiceTest {

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private CMSProviderPort cms;

    @InjectMocks
    private WatchdogService watchdogService;

    @Test
    void shouldReconcileClientCountsFromTaskAggregates() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of("clientA", 3));
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("clientA", 5));

        watchdogService.reconcile();

        verify(clientCounts).upsertCount("clientA", 3);
        verify(cms).rebuild(Map.of("clientA", 3));
    }

    @Test
    void shouldZeroStoredCountsWhenNoInFlightTasksRemain() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of());
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("clientA", 2));

        watchdogService.reconcile();

        verify(clientCounts).upsertCount("clientA", 0);
        verify(cms).rebuild(Map.of());
    }

    @Test
    void shouldNotUpsertWhenCountsAlreadyMatchTasks() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of("clientA", 3));
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("clientA", 3));

        watchdogService.reconcile();

        verify(clientCounts, never()).upsertCount(anyString(), anyInt());
        verify(cms).rebuild(Map.of("clientA", 3));
    }
}
