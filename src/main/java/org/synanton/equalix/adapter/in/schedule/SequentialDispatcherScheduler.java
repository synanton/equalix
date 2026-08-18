package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.SequentialDispatcherService;

@Component
@ConditionalOnProperty(name = "app.queue.sequential.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SequentialDispatcherScheduler {

    private final SequentialDispatcherService sequentialDispatcherService;

    @Scheduled(fixedDelayString = "${app.queue.sequential.dispatcher-interval}")
    @SchedulerLock(name = "sequentialDispatcher", lockAtMostFor = "5s", lockAtLeastFor = "25ms")
    public void run() {
        sequentialDispatcherService.dispatch();
    }
}
