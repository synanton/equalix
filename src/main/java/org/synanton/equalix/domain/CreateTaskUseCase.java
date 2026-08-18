package org.synanton.equalix.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@Component
@RequiredArgsConstructor
public class CreateTaskUseCase implements TaskIngestionPort {

    private final TaskRepositoryPort taskRepository;
    private final ClientSequenceStateRepositoryPort sequenceStateRepository;
    private final QueueProperties queueProperties;
    private final Clock clock;

    @Override
    public Task createTask(
        String fairnessKey,
        BigDecimal weight,
        byte[] payload,
        boolean isSequential,
        @Nullable Long sequenceNumber,
        @Nullable UUID dependsOnTaskId,
        boolean requiresPreviousResult
    ) {
        if (fairnessKey == null || fairnessKey.isBlank()) {
            throw new IllegalArgumentException("fairnessKey must not be blank");
        }
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length > queueProperties.getMaxPayloadBytes()) {
            throw new IllegalArgumentException(
                "payload exceeds max size of " + queueProperties.getMaxPayloadBytes() + " bytes");
        }
        if (isSequential && sequenceNumber == null) {
            throw new IllegalArgumentException("sequenceNumber is required when sequential is true");
        }

        Instant now = Instant.now(clock);
        Task task = new Task()
            .setId(UUID.randomUUID())
            .setFairnessKey(fairnessKey)
            .setWeight(weight)
            .setPayload(payload)
            .setStatus(TaskStatus.RECEIVED)
            .setRetryCount(0)
            .setCreatedAt(now)
            .setUpdatedAt(now)
            .setSequential(isSequential)
            .setSequenceNumber(sequenceNumber)
            .setDependsOnTaskId(dependsOnTaskId)
            .setRequiresPreviousResult(requiresPreviousResult);

        Task saved = taskRepository.save(task);
        if (isSequential) {
            sequenceStateRepository.findOrCreate(fairnessKey);
        }
        return saved;
    }
}
