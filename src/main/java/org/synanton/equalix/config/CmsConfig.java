package org.synanton.equalix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.synanton.equalix.adapter.out.cms.CountMinSketchAdapter;
import org.synanton.equalix.adapter.out.cms.HierarchicalCmsProvider;
import org.synanton.equalix.adapter.out.cms.RedisCMSAdapter;
import org.synanton.equalix.adapter.out.cms.TransactionAwareCmsProvider;
import org.synanton.equalix.config.properties.QueueProperties;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.service.FairnessHierarchy;

/**
 * Selects the CMS implementation. Both are wrapped so updates apply only when their transaction commits, and in
 * hierarchical mode so that internal nodes are counted too.
 */
@Configuration
public class CmsConfig {

    @Bean
    @ConditionalOnProperty(name = "app.queue.cms.mode", havingValue = "local", matchIfMissing = true)
    public CMSProviderPort localCmsProvider(QueueProperties props, FairnessHierarchy hierarchy) {
        return decorate(new CountMinSketchAdapter(props), hierarchy);
    }

    @Bean
    @ConditionalOnProperty(name = "app.queue.cms.mode", havingValue = "redis")
    public CMSProviderPort redisCmsProvider(QueueProperties props, StringRedisTemplate redisTemplate,
        FairnessHierarchy hierarchy) {
        return decorate(new RedisCMSAdapter(props, redisTemplate), hierarchy);
    }

    private static CMSProviderPort decorate(CMSProviderPort sketch, FairnessHierarchy hierarchy) {
        CMSProviderPort transactional = new TransactionAwareCmsProvider(sketch);
        return hierarchy.isEnabled() ? new HierarchicalCmsProvider(transactional, hierarchy) : transactional;
    }
}
