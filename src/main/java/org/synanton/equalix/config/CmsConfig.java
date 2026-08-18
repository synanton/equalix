package org.synanton.equalix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.synanton.equalix.adapter.out.cms.CountMinSketchAdapter;
import org.synanton.equalix.adapter.out.cms.RedisCMSAdapter;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.port.out.CMSProviderPort;

@Configuration
public class CmsConfig {

    @Bean
    @ConditionalOnProperty(name = "app.queue.cms.mode", havingValue = "local", matchIfMissing = true)
    public CMSProviderPort localCmsProvider(QueueProperties props) {
        return new CountMinSketchAdapter(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.queue.cms.mode", havingValue = "redis")
    public CMSProviderPort redisCmsProvider(QueueProperties props, StringRedisTemplate redisTemplate) {
        return new RedisCMSAdapter(props, redisTemplate);
    }
}
