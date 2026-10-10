package org.synanton.equalix.config;

import java.time.Duration;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
@Slf4j
public class ShedLockConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.scheduling", name = "distributed-locks",
        havingValue = "true", matchIfMissing = true)
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.scheduling", name = "distributed-locks", havingValue = "false")
    public LockProvider noOpLockProvider() {
        // A node cannot count its peers, so disabling locks cannot be validated — only
        // announced. With 2+ instances, ticks run concurrently on every instance: task rows
        // stay safe via SKIP LOCKED, but CMS counts and virtual-time charges double-apply
        // until the watchdog reconciles.
        log.warn("app.scheduling.distributed-locks=false: scheduler runs UNLOCKED. "
            + "Safe only with exactly one instance; with 2+ instances fairness accounting "
            + "double-applies between watchdog runs.");
        return new NoOpLockProvider();
    }

    /** Minimum hold time prevents another instance from acquiring the lock immediately after release. */
    public static final Duration DEFAULT_LOCK_AT_LEAST = Duration.ofMillis(50);
}
