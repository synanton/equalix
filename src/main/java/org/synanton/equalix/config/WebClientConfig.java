package org.synanton.equalix.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.equalix.config.properties.ExecutorProperties;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient remoteExecutorWebClient(ExecutorProperties executorProperties) {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, executorProperties.getConnectTimeoutMs())
            .responseTimeout(Duration.ofMillis(executorProperties.getReadTimeoutMs()));
        return WebClient.builder()
            .baseUrl(executorProperties.getBaseUrl())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
