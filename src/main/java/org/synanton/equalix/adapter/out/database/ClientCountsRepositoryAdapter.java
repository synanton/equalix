package org.synanton.equalix.adapter.out.database;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.model.ClientCounts;
import org.synanton.equalix.domain.port.out.ClientCountsRepositoryPort;

@Component
@RequiredArgsConstructor
public class ClientCountsRepositoryAdapter implements ClientCountsRepositoryPort {

    private final ClientCountsJpaRepository jpaRepository;

    @Override
    public void incrementInFlight(String fairnessKey) {
        jpaRepository.incrementInFlight(fairnessKey);
    }

    @Override
    public void decrementInFlight(String fairnessKey) {
        jpaRepository.decrementInFlight(fairnessKey);
    }

    @Override
    public void upsertCount(String fairnessKey, int count) {
        jpaRepository.upsertCount(fairnessKey, count);
    }

    @Override
    public List<ClientCounts> findAll() {
        return jpaRepository.findAll().stream()
            .map(entity -> new ClientCounts()
                .setFairnessKey(entity.getFairnessKey())
                .setInFlightCount(entity.getInFlightCount())
                .setUpdatedAt(entity.getUpdatedAt()))
            .toList();
    }

    @Override
    public long totalInFlight() {
        return jpaRepository.totalInFlight();
    }

    @Override
    public Map<String, Integer> findAllAsMap() {
        return jpaRepository.findAll().stream()
            .collect(Collectors.toMap(
                entity -> entity.getFairnessKey(),
                entity -> entity.getInFlightCount()));
    }
}
