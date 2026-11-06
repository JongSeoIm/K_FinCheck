package com.kfincheck.realtime;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
@EnableWebSocket
@EnableConfigurationProperties(StreamProperties.class)
@ConditionalOnProperty(name = "stream.enabled", havingValue = "true")
public class RealtimeConfig implements WebSocketConfigurer {
    private final QuoteSocketHandler handler;
    public RealtimeConfig(QuoteSocketHandler handler) { this.handler = handler; }
    @Bean
    public NewTopic quotesTopic(StreamProperties properties) {
        return TopicBuilder.name(properties.getTopic()).partitions(1).replicas(1).build();
    }
    @Bean
    public DefaultErrorHandler quoteErrorHandler() {
        // Never silently recover/skip a failed DB write after a fixed number of attempts.
        var handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        handler.setClassifications(java.util.Map.of(Exception.class, true), true);
        return handler;
    }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/quotes")
                .setAllowedOrigins("http://localhost:3000", "http://127.0.0.1:3000", "http://localhost:3001", "http://127.0.0.1:3001");
    }
}
