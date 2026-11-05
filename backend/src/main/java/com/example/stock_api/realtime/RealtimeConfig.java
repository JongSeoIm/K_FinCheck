package com.example.stock_api.realtime;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** stream.enabled=true 일 때만 실시간(KIS → Kafka → QuoteStore) 구성을 켭니다. */
@Configuration
@EnableConfigurationProperties(RealtimeProperties.class)
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class RealtimeConfig {

    @Bean
    public NewTopic quotesTopic(RealtimeProperties properties) {
        return TopicBuilder.name(properties.getTopic()).partitions(1).replicas(1).build();
    }
}
