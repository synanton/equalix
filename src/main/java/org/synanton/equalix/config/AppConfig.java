package org.synanton.equalix.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.equalix.config.properties.*;

@Configuration
@EnableConfigurationProperties({
        QueueProperties.class,
        AdaptiveRpsProperties.class,
        WatchdogProperties.class,
        SequentialProperties.class,
        CmsProperties.class,
        ExecutorProperties.class,
        SecurityProperties.class
})
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
