package com.hk.stock.market;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.QuoteDto;

import jakarta.annotation.PreDestroy;

/**
 * Kafka 발행: KIS 체결 → stock-quotes-v1
 * - 메시지 키를 종목 코드로 해 같은 종목의 시세가 같은 파티션으로 들어가게 한다.
 * - 재시도는 Kafka 프로듀서가 delivery.timeout.ms 안에서 처리한다. 끝내 실패하면 성공으로 세지 않고 로그를 남긴다.
 * - KIS 연결이 묶이지 않도록 별도 스레드에서 보낸다. Kafka 가 오래 멈춰 대기열이 차면 오래된 시세부터 버린다.
 * - 5초마다 확인 메시지를 보낸다. 장외 시간처럼 체결이 없어도 소비 쪽이 Kafka 연결 상태를 알 수 있다.
 */
@Component
public class QuoteKafkaProducer {

	private static final Logger log = LoggerFactory.getLogger(QuoteKafkaProducer.class);
	private static final int QUEUE_SIZE = 2_000;
	private static final long LOG_EVERY_MS = 10_000;

	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper objectMapper;
	private final String quoteTopic;
	private final String heartbeatTopic;
	private final ThreadPoolExecutor executor;
	private final String runId = Long.toString(System.currentTimeMillis());
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();
	private final AtomicLong lastFailureLog = new AtomicLong();

	public QuoteKafkaProducer(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
			@Value("${market.kafka.quote-topic}") String quoteTopic,
			@Value("${market.kafka.heartbeat-topic}") String heartbeatTopic) {
		this.kafka = kafka;
		this.objectMapper = objectMapper;
		this.quoteTopic = quoteTopic;
		this.heartbeatTopic = heartbeatTopic;
		this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE),
				r -> {
					Thread t = new Thread(r, "kafka-publish");
					t.setDaemon(true);
					return t;
				},
				new ThreadPoolExecutor.DiscardOldestPolicy());
	}

	public void publish(QuoteDto tick) {
		QuoteDto event = tick.toBuilder()
				.eventId(tick.getSymbol() + "-" + runId + "-" + sequence.incrementAndGet())
				.build();
		executor.execute(() -> send(quoteTopic, event.getSymbol(), event));
	}

	@Scheduled(fixedDelay = 5_000)
	public void publishHeartbeat() {
		executor.execute(() -> send(heartbeatTopic, "feed", Map.of("sentAt", System.currentTimeMillis())));
	}

	public long failedCount() {
		return failed.get();
	}

	private void send(String topic, String key, Object value) {
		try {
			kafka.send(topic, key, objectMapper.writeValueAsString(value)).whenComplete((result, error) -> {
				if (error != null) fail(error.getMessage());
			});
		} catch (JsonProcessingException e) {
			log.warn("시세 변환 실패: {}", e.getMessage());
		} catch (RuntimeException e) {
			fail(e.getMessage());	//브로커 연결 대기 시간(max.block.ms) 초과 등
		}
	}

	//Kafka 가 멈추면 같은 오류가 계속 나오므로 10초에 한 번만 남긴다
	private void fail(String message) {
		long count = failed.incrementAndGet();
		long now = System.currentTimeMillis();
		long last = lastFailureLog.get();
		if (now - last > LOG_EVERY_MS && lastFailureLog.compareAndSet(last, now)) {
			log.warn("Kafka 발행 실패 (누적 {}건): {}", count, message);
		}
	}

	@PreDestroy
	public void stop() {
		executor.shutdown();
	}
}
