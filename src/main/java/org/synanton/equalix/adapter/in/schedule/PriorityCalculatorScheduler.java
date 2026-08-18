package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.PriorityCalculatorService;

@Component
@RequiredArgsConstructor
public class PriorityCalculatorScheduler {

    private final PriorityCalculatorService priorityCalculatorService;

    @Scheduled(fixedDelayString = "${app.queue.priority-calc-interval}")
    @SchedulerLock(name = "priorityCalculator", lockAtMostFor = "5s", lockAtLeastFor = "50ms")
    public void run() {
        priorityCalculatorService.run();
    }
}
