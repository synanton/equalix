package org.synanton.equalix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.CreateTaskUseCase;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateTaskUseCaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskRepositoryPort taskRepository;
    @Mock
    private ClientSequenceStateRepositoryPort sequenceStateRepository;

    private CreateTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxPayloadBytes(1024);
        useCase = new CreateTaskUseCase(
            taskRepository, sequenceStateRepository, queueProperties,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sequenceStateRepository.findOrCreate(any()))
            .thenAnswer(inv -> new ClientSequenceState().setFairnessKey(inv.getArgument(0)));
    }

    @Test
    void shouldCreateTaskWithStatusReceived() {
        Task result = useCase.createTask("client1", new BigDecimal("1.0"), new byte[]{1}, false, null, null, false);

        assertThat(result).isEqualTo(new Task()
            .setId(result.getId())
            .setFairnessKey("client1")
            .setWeight(new BigDecimal("1.0"))
            .setPayload(new byte[]{1})
            .setStatus(TaskStatus.RECEIVED)
            .setRetryCount(0)
            .setCreatedAt(FIXED_NOW)
            .setUpdatedAt(FIXED_NOW)
            .setSequential(false)
            .setRequiresPreviousResult(false));
        verify(sequenceStateRepository, never()).findOrCreate(any());
    }

    @Test
    void shouldCreateSequenceStateForSequentialTask() {
        useCase.createTask("client1", new BigDecimal("1.0"), new byte[]{1}, true, 1L, null, false);

        verify(sequenceStateRepository).findOrCreate("client1");
    }

    @Test
    void shouldRejectSequentialTaskWithoutSequenceNumber() {
        assertThatThrownBy(() -> useCase.createTask("client1", new BigDecimal("1.0"), new byte[]{1},
                true, null, null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sequenceNumber");
    }

    @Test
    void shouldRejectNullFairnessKey() {
        assertThatThrownBy(() -> useCase.createTask(null, new BigDecimal("1.0"), new byte[]{1},
                false, null, null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fairnessKey");
    }

    @Test
    void shouldRejectBlankFairnessKey() {
        assertThatThrownBy(() -> useCase.createTask("  ", new BigDecimal("1.0"), new byte[]{1},
                false, null, null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveWeight() {
        assertThatThrownBy(() -> useCase.createTask("client1", new BigDecimal("0.0"), new byte[]{1},
                false, null, null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weight");
    }

    @Test
    void shouldRejectNullPayload() {
        assertThatThrownBy(() -> useCase.createTask("client1", new BigDecimal("1.0"), null,
                false, null, null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
    }

    @Test
    void shouldRejectOversizedPayload() {
        assertThatThrownBy(() -> useCase.createTask("client1", new BigDecimal("1.0"), new byte[2048],
                false, null, null, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload exceeds max size");
    }
}
