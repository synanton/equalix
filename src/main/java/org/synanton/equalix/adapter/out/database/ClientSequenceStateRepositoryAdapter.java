package org.synanton.equalix.adapter.out.database;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.synanton.equalix.adapter.out.database.entity.ClientSequenceStateEntity;
import org.synanton.equalix.domain.model.ClientSequenceState;
import org.synanton.equalix.domain.port.out.ClientSequenceStateRepositoryPort;

@Component
@RequiredArgsConstructor
public class ClientSequenceStateRepositoryAdapter implements ClientSequenceStateRepositoryPort {

    private final ClientSequenceStateJpaRepository jpaRepository;
    private final Clock clock;

    @Override
    public ClientSequenceState findOrCreate(String fairnessKey) {
        return jpaRepository.findById(fairnessKey)
            .map(this::toDomain)
            .orElseGet(() -> {
                ClientSequenceStateEntity entity = new ClientSequenceStateEntity()
                    .setFairnessKey(fairnessKey)
                    .setLastCompletedSequence(0L)
                    .setLastDispatchedSequence(0L)
                    .setBlocked(false)
                    .setUpdatedAt(clock.instant());
                return toDomain(jpaRepository.save(entity));
            });
    }

    @Override
    public Optional<ClientSequenceState> findByFairnessKey(String fairnessKey) {
        return jpaRepository.findById(fairnessKey).map(this::toDomain);
    }

    @Override
    public ClientSequenceState save(ClientSequenceState state) {
        return toDomain(jpaRepository.save(toEntity(state)));
    }

    @Override
    public List<ClientSequenceState> findReadyClients() {
        return jpaRepository.findReadyClients().stream().map(this::toDomain).toList();
    }

    @Override
    public List<ClientSequenceState> findBlockedClients() {
        return jpaRepository.findByIsBlockedTrue().stream().map(this::toDomain).toList();
    }

    private ClientSequenceState toDomain(ClientSequenceStateEntity entity) {
        return new ClientSequenceState()
            .setFairnessKey(entity.getFairnessKey())
            .setLastCompletedSequence(entity.getLastCompletedSequence())
            .setLastDispatchedSequence(entity.getLastDispatchedSequence())
            .setCurrentExecutingTaskId(entity.getCurrentExecutingTaskId())
            .setBlocked(entity.isBlocked())
            .setBlockedAt(entity.getBlockedAt())
            .setUpdatedAt(entity.getUpdatedAt());
    }

    private ClientSequenceStateEntity toEntity(ClientSequenceState state) {
        return new ClientSequenceStateEntity()
            .setFairnessKey(state.getFairnessKey())
            .setLastCompletedSequence(state.getLastCompletedSequence())
            .setLastDispatchedSequence(state.getLastDispatchedSequence())
            .setCurrentExecutingTaskId(state.getCurrentExecutingTaskId())
            .setBlocked(state.isBlocked())
            .setBlockedAt(state.getBlockedAt())
            .setUpdatedAt(state.getUpdatedAt());
    }
}
