package org.synanton.equalix.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.CmsErrorRecorder;

/**
 * Opt-in sampler for CMS estimation error during load tests. Not under ShedLock: with the local CMS each instance
 * holds its own sketch, so every instance samples its own.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.queue.cms.error-sampling", name = "enabled", havingValue = "true")
public class CmsErrorSamplingScheduler {

    private final CmsErrorRecorder cmsErrorRecorder;

    @Scheduled(fixedDelayString = "${app.queue.cms.error-sampling.interval-ms}")
    public void run() {
        cmsErrorRecorder.sample();
    }
}
