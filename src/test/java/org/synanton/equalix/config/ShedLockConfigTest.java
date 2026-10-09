package org.synanton.equalix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;

class ShedLockConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ShedLockConfig.class));

    @Test
    void noOpProviderWhenDistributedLocksDisabled() {
        runner.withPropertyValues("app.scheduling.distributed-locks=false")
            .run(context -> assertThat(context.getBean(LockProvider.class))
                .isInstanceOf(NoOpLockProvider.class));
    }

    @Test
    void jdbcProviderByDefault() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
            .run(context -> assertThat(context.getBean(LockProvider.class))
                .isNotInstanceOf(NoOpLockProvider.class));
    }

    @Test
    void noOpLockAcquiresAndReleasesSilently() {
        LockProvider provider = new NoOpLockProvider();
        assertThat(provider.lock(new LockConfiguration(Instant.now(), "test", Duration.ofSeconds(1),
            Duration.ofMillis(1)))).isPresent();
        provider.lock(new LockConfiguration(Instant.now(), "test", Duration.ofSeconds(1),
            Duration.ofMillis(1))).ifPresent(lock -> lock.unlock());
    }
}
