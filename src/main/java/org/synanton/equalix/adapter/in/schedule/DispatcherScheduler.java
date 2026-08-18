package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.DispatcherService;

@Component
@RequiredArgsConstructor
public class DispatcherScheduler {

    private final DispatcherService dispatcherService;

    @Scheduled(fixedDelayString = "${app.queue.dispatcher-interval}")
    @SchedulerLock(name = "dispatcher", lockAtMostFor = "5s", lockAtLeastFor = "25ms")
    public void run() {
        dispatcherService.dispatch();
    }
}
