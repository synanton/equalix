package org.synanton.equalix.adapter.in.startup;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.service.WatchdogService;

/**
 * Rebuilds the CMS from in-flight tasks at startup. Without this, a restarted instance's local sketch is empty
 * and underestimates every key until the first watchdog run. A new Redis layout version also starts empty.
 */
@Component
@RequiredArgsConstructor
public class CmsWarmUpListener {

    private final WatchdogService watchdogService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        watchdogService.warmUpCms();
    }
}
