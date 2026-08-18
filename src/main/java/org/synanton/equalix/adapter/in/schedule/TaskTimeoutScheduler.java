package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.TaskTimeoutService;

@Component
@RequiredArgsConstructor
public class TaskTimeoutScheduler {

    private final TaskTimeoutService taskTimeoutService;

    @Scheduled(fixedDelayString = "${app.queue.dispatcher-interval}")
    @SchedulerLock(name = "taskTimeout", lockAtMostFor = "5s", lockAtLeastFor = "25ms")
    public void run() {
        taskTimeoutService.expireTimedOutTasks();
    }
}
