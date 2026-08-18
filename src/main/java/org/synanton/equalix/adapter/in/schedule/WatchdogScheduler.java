package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.WatchdogService;

@Component
@RequiredArgsConstructor
public class WatchdogScheduler {

    private final WatchdogService watchdogService;

    @Scheduled(fixedRateString = "#{${app.watchdog.interval-minutes} * 60000}")
    @SchedulerLock(name = "watchdog", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        watchdogService.reconcile();
    }
}
