package com.hk.stock.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.service.QuoteStore;

/**
 * Kafka 소비: stock-quotes-v1 → QuoteStore (최신 시세·봉 집계)
 * 재시작하면 마지막으로 처리한 위치부터 이어서 읽는다. 다시 읽은 시세는 QuoteStore 가 걸러낸다.
 * 형식이 잘못된 메시지 하나 때문에 소비가 멈추지 않도록 건너뛴다.
 */
@Component
public class QuoteKafkaConsumer {

	private static final Logger log = LoggerFactory.getLogger(QuoteKafkaConsumer.class);

	private final QuoteStore quoteStore;
	private final ObjectMapper objectMapper;

	public QuoteKafkaConsumer(QuoteStore quoteStore, ObjectMapper objectMapper) {
		this.quoteStore = quoteStore;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(topics = "${market.kafka.quote-topic}")
	public void onQuote(String message) {
		try {
			quoteStore.onTick(objectMapper.readValue(message, QuoteDto.class));
		} catch (Exception e) {
			log.warn("시세 메시지 처리 실패, 건너뜀: {}", e.getMessage());
		}
	}

	@KafkaListener(topics = "${market.kafka.heartbeat-topic}")
	public void onHeartbeat(String message) {
		quoteStore.onKafkaHeartbeat();
	}
}
