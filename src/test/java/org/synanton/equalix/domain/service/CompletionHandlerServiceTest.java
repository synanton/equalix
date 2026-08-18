package org.synanton.equalix.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;
import org.synanton.equalix.domain.port.out.PerformanceMonitorPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
class CompletionHandlerServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:01Z");
    private static final Instant DISPATCH_AT = FIXED_NOW.minusMillis(250);

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private CMSProviderPort cms;
    @Mock
    private ClientCountsRepositoryPort clientCounts;
    @Mock
    private PerformanceMonitorPort performanceMonitor;

    private CompletionHandlerService service;

    @BeforeEach
    void setUp() {
        service = new CompletionHandlerService(taskRepository, cms, clientCounts, performanceMonitor,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldMarkTaskSucceededAndDecrementCounts() {
        Task task = buildDispatched();
        byte[] result = new byte[]{9};

        service.handle(task, true, result, null);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.getResult()).isSameAs(result);
        assertThat(task.getLastError()).isNull();
        assertThat(task.getCompletedAt()).isEqualTo(FIXED_NOW);
        assertThat(task.getUpdatedAt()).isEqualTo(FIXED_NOW);

        verify(taskRepository).save(task);
        verify(cms).add("k", -1L);
        verify(clientCounts).decrementInFlight("k");
        verify(performanceMonitor).recordCompletion(eq("k"), eq(250L), eq(true));
    }

    @Test
    void shouldMarkTaskFailedAndRecordError() {
        Task task = buildDispatched();

        service.handle(task, false, null, "downstream 500");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getLastError()).isEqualTo("downstream 500");
        assertThat(task.getResult()).isNull();
        verify(performanceMonitor).recordCompletion(eq("k"), eq(250L), eq(false));
        verify(cms).add("k", -1L);
        verify(clientCounts).decrementInFlight("k");
    }

    @Test
    void shouldRecordZeroDurationWhenUpdatedAtMissing() {
        Task task = buildDispatched().setUpdatedAt(null);

        service.handle(task, true, null, null);

        verify(performanceMonitor).recordCompletion(eq("k"), eq(0L), eq(true));
    }

    @Test
    void shouldIgnoreDuplicateCompletionForTerminalTask() {
        Task task = buildDispatched().setStatus(TaskStatus.SUCCEEDED);

        service.handle(task, true, null, null);

        verifyNoInteractions(taskRepository, cms, clientCounts, performanceMonitor);
    }

    @Test
    void shouldRejectCompletionWhenTaskIsNotInFlight() {
        Task task = buildDispatched().setStatus(TaskStatus.QUEUED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handle(task, true, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not in-flight");
        verify(taskRepository, never()).save(task);
    }

    private Task buildDispatched() {
        return new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey("k")
            .setStatus(TaskStatus.DISPATCHED)
            .setPayload(new byte[]{1})
            .setCreatedAt(DISPATCH_AT)
            .setUpdatedAt(DISPATCH_AT);
    }
}
