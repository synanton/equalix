package org.synanton.equalix.adapter.out.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.synanton.equalix.adapter.out.database.entity.TaskEntity;
import org.synanton.equalix.domain.model.QueuedLeaf;
import org.synanton.equalix.domain.model.Task;
import org.synanton.equalix.domain.model.TaskStatus;
import org.synanton.equalix.domain.port.out.TaskRepositoryPort;

@Component
@RequiredArgsConstructor
public class TaskRepositoryAdapter implements TaskRepositoryPort {

    private final TaskJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Task save(Task task) {
        return toDomain(jpaRepository.save(toEntity(task)));
    }

    @Override
    public Optional<Task> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Task> findByStatus(TaskStatus status, int limit) {
        return jpaRepository.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(0, limit))
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Task> findAndLockDispatchable(int limit, @Nullable Integer maxPerClient) {
        return jpaRepository.findAndLockDispatchable(limit, maxPerClient)
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Task> findAndLockOldestDispatchable(int limit, @Nullable Integer maxPerClient) {
        return jpaRepository.findAndLockOldestDispatchable(limit, maxPerClient)
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<QueuedLeaf> findQueuedLeaves() {
        return jpaRepository.findQueuedLeaves().stream()
            .map(row -> new QueuedLeaf(
                (String) row[0],
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).doubleValue(),
                ((Number) row[4]).intValue()))
            .toList();
    }

    @Override
    public List<Task> findAndLockQueuedHeads(Map<String, Integer> limitsByKey) {
        if (limitsByKey.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> limits = new ArrayList<>();
        limitsByKey.forEach((fairnessKey, limit) -> limits.add(Map.of("key", fairnessKey, "limit", limit)));
        try {
            return jpaRepository.findAndLockQueuedHeads(objectMapper.writeValueAsString(limits))
                .stream().map(this::toDomain).toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise dispatch limits", exception);
        }
    }

    @Override
    public List<Task> findStarvedTasks(long olderThanMs, int limit) {
        return jpaRepository.findStarvedTasks(olderThanMs, limit)
            .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<Task> findNextSequentialTask(String fairnessKey, long sequenceNumber, TaskStatus status) {
        return jpaRepository.findByFairnessKeyAndSequenceNumberAndStatus(fairnessKey, sequenceNumber, status)
            .map(this::toDomain);
    }

    @Override
    public List<Task> findTasksWaitingForPreviousResult() {
        return jpaRepository.findTasksWaitingForPreviousResult(TaskStatus.QUEUED)
            .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Task> findByFairnessKey(String fairnessKey, @Nullable TaskStatus status) {
        if (status != null) {
            return jpaRepository.findByFairnessKeyAndStatusOrderByCreatedAtAsc(fairnessKey, status)
                .stream().map(this::toDomain).toList();
        }
        return jpaRepository.findByFairnessKeyOrderByCreatedAtAsc(fairnessKey)
            .stream().map(this::toDomain).toList();
    }

    @Override
    public int updateStatusBatch(List<UUID> ids, TaskStatus newStatus) {
        return jpaRepository.updateStatusBatch(ids, newStatus);
    }

    @Override
    public Map<String, Integer> countInFlightByFairnessKey() {
        Map<String, Integer> counts = new HashMap<>();
        List<Object[]> rows = jpaRepository.countByStatusesGroupByFairnessKey(
            List.of(TaskStatus.DISPATCHED, TaskStatus.COMMITTED));
        for (Object[] row : rows) {
            counts.put((String) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    @Override
    public List<Task> findTimedOutInFlight(long olderThanMs, int limit) {
        return jpaRepository.findTimedOutInFlight(olderThanMs, limit)
            .stream().map(this::toDomain).toList();
    }

    private Task toDomain(TaskEntity entity) {
        return new Task()
            .setId(entity.getId())
            .setFairnessKey(entity.getFairnessKey())
            .setWeight(entity.getWeight())
            .setStatus(entity.getStatus())
            .setPriority(entity.getPriority())
            .setVirtualFinish(entity.getVirtualFinish())
            .setPayload(entity.getPayload())
            .setCreatedAt(entity.getCreatedAt())
            .setUpdatedAt(entity.getUpdatedAt())
            .setCompletedAt(entity.getCompletedAt())
            .setRetryCount(entity.getRetryCount())
            .setLastError(entity.getLastError())
            .setResult(entity.getResult())
            .setVersion(entity.getVersion())
            .setSequenceNumber(entity.getSequenceNumber())
            .setDependsOnTaskId(entity.getDependsOnTaskId())
            .setSequential(entity.isSequential())
            .setPreviousResult(entity.getPreviousResult())
            .setRequiresPreviousResult(entity.isRequiresPreviousResult());
    }

    private TaskEntity toEntity(Task task) {
        return new TaskEntity()
            .setId(task.getId())
            .setFairnessKey(task.getFairnessKey())
            .setWeight(task.getWeight())
            .setStatus(task.getStatus())
            .setPriority(task.getPriority())
            .setVirtualFinish(task.getVirtualFinish())
            .setPayload(task.getPayload())
            .setCreatedAt(task.getCreatedAt())
            .setUpdatedAt(task.getUpdatedAt())
            .setCompletedAt(task.getCompletedAt())
            .setRetryCount(task.getRetryCount())
            .setLastError(task.getLastError())
            .setResult(task.getResult())
            .setVersion(task.getVersion())
            .setSequenceNumber(task.getSequenceNumber())
            .setDependsOnTaskId(task.getDependsOnTaskId())
            .setSequential(task.isSequential())
            .setPreviousResult(task.getPreviousResult())
            .setRequiresPreviousResult(task.isRequiresPreviousResult());
    }
}
