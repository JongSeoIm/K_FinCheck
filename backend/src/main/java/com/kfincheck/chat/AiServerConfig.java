package com.kfincheck.chat;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiServerConfig {
    @Bean
    RestClient aiServerRestClient(
            RestClient.Builder builder,
            @Value("${ai-server.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${ai-server.connect-timeout:2s}") Duration connectTimeout,
            @Value("${ai-server.read-timeout:30s}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
