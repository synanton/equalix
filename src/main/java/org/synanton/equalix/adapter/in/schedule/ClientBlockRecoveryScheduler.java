package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.ClientBlockRecoveryService;

@Component
@ConditionalOnProperty(name = "app.queue.sequential.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ClientBlockRecoveryScheduler {

    private final ClientBlockRecoveryService clientBlockRecoveryService;

    @Scheduled(fixedDelayString = "${app.queue.sequential.block-recovery-interval}")
    @SchedulerLock(name = "clientBlockRecovery", lockAtMostFor = "5m", lockAtLeastFor = "1s")
    public void run() {
        clientBlockRecoveryService.recover();
    }
}
