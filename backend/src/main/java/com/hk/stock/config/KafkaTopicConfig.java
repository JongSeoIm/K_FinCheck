package com.hk.stock.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

//서버가 시작할 때 토픽을 만든다. 단일 브로커라 복제본은 1개이고 고가용성 구성이 아니다
@Configuration
public class KafkaTopicConfig {

	@Bean
	public NewTopic quoteTopic(@Value("${market.kafka.quote-topic}") String name) {
		return TopicBuilder.name(name).partitions(3).replicas(1).build();
	}

	@Bean
	public NewTopic heartbeatTopic(@Value("${market.kafka.heartbeat-topic}") String name) {
		return TopicBuilder.name(name).partitions(1).replicas(1).build();
	}
}
