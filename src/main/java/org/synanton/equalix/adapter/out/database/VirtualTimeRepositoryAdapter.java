package org.synanton.equalix.adapter.out.database;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.model.VirtualTimeState;
import org.synanton.equalix.domain.port.out.VirtualTimeRepositoryPort;

@Component
@RequiredArgsConstructor
public class VirtualTimeRepositoryAdapter implements VirtualTimeRepositoryPort {

    private final ClientVirtualTimeJpaRepository clientRepository;
    private final SchedulerVirtualClockJpaRepository clockRepository;

    @Override
    public double findSystemVirtualTime() {
        return clockRepository.findSystemVirtualTime();
    }

    @Override
    public double reserveFinishTag(String fairnessKey, double systemVirtualTime, double increment) {
        return clientRepository.reserveFinishTag(fairnessKey, systemVirtualTime, increment);
    }

    @Override
    public void advanceClientVirtualTime(String fairnessKey, double finishTag) {
        clientRepository.advanceVirtualTime(fairnessKey, finishTag);
    }

    @Override
    public void advanceSystemVirtualTime(double finishTag) {
        clockRepository.advance(finishTag);
    }

    @Override
    public Optional<VirtualTimeState> findByFairnessKey(String fairnessKey) {
        return clientRepository.findById(fairnessKey)
            .map(entity -> new VirtualTimeState()
                .setFairnessKey(entity.getFairnessKey())
                .setVirtualTime(entity.getVirtualTime())
                .setVirtualFinish(entity.getVirtualFinish())
                .setUpdatedAt(entity.getUpdatedAt()));
    }
}
