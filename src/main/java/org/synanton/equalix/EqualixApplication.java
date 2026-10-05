package org.synanton.equalix;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class EqualixApplication {

    private static final Logger log = LoggerFactory.getLogger(EqualixApplication.class);

    public static void main(String[] args) {
        // Startup milestone for the differential matrix's
        // runtime-characterization row (informational, never gated):
        // main-entry here, context-built below at ApplicationReadyEvent.
        // The harness records spawn/ready/first-dispatch; these lines give
        // the internal phase breakdown when a cell needs drill-down.
        log.info("startup milestone phase=main-entry at={} nanoTime={}",
                Instant.now(), System.nanoTime());
        SpringApplication.run(EqualixApplication.class, args);
    }

    @Component
    static class StartupMilestones {
        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            log.info("startup milestone phase=context-ready at={} nanoTime={}",
                    Instant.now(), System.nanoTime());
        }
    }
}
