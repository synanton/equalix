package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.CmsErrorStatistics;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class CmsErrorRecorderTest {

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private PerformanceMonitorPort performanceMonitor;

    @InjectMocks
    private CmsErrorRecorder recorder;

    @Test
    void shouldSampleErrorAgainstInFlightTasksIncludingPhantomKeys() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of("exact", 3, "over", 2, "under", 4));
        when(clientCounts.findAllAsMap()).thenReturn(Map.of("exact", 3, "phantom", 0));
        when(cms.estimateCount("exact")).thenReturn(3L);
        when(cms.estimateCount("over")).thenReturn(5L);
        when(cms.estimateCount("under")).thenReturn(1L);
        when(cms.estimateCount("phantom")).thenReturn(2L);

        CmsErrorStatistics statistics = recorder.sample();

        assertThat(statistics.histogram()).isEqualTo(Map.of(-3L, 1L, 0L, 1L, 2L, 1L, 3L, 1L));
        verify(performanceMonitor).recordCmsEstimationError(0L);
        verify(performanceMonitor).recordCmsEstimationError(3L);
        verify(performanceMonitor).recordCmsEstimationError(-3L);
        verify(performanceMonitor).recordCmsEstimationError(2L);
        verifyNoMoreInteractions(performanceMonitor);
    }

    @Test
    void shouldReturnEmptyDistributionWhenNothingIsTracked() {
        when(taskRepository.countInFlightByFairnessKey()).thenReturn(Map.of());
        when(clientCounts.findAllAsMap()).thenReturn(Map.of());

        assertThat(recorder.sample().count()).isZero();
        verifyNoInteractions(cms, performanceMonitor);
    }
}
