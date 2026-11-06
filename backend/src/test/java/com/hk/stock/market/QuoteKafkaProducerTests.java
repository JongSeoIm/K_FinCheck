package com.hk.stock.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.QuoteDto;

//실제 Kafka 브로커(내장)로 발행 → 소비까지 확인
class QuoteKafkaProducerTests {

	private static final String TOPIC = "stock-quotes-v1";

	@Test
	void 종목_코드를_키로_공통_시세를_발행한다() throws Exception {
		EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 3, TOPIC, "stock-feed-heartbeat-v1");
		broker.afterPropertiesSet();
		try {
			Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
			producerProps.put("key.serializer", StringSerializer.class);
			producerProps.put("value.serializer", StringSerializer.class);
			KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
			ObjectMapper mapper = new ObjectMapper();
			QuoteKafkaProducer producer = new QuoteKafkaProducer(template, mapper, TOPIC, "stock-feed-heartbeat-v1");

			Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test", "true", broker);
			consumerProps.put("auto.offset.reset", "earliest");
			Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer())
					.createConsumer();
			consumer.subscribe(List.of(TOPIC));

			producer.publish(QuoteDto.builder().symbol("005930").price(new BigDecimal("71000")).eventTime("2026-11-05T09:30:01+09:00").source("DEMO").build());
			producer.publish(QuoteDto.builder().symbol("005930").price(new BigDecimal("71100")).eventTime("2026-11-05T09:30:02+09:00").source("DEMO").build());

			ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 2);
			assertEquals(2, records.count());

			int partition = -1;
			long lastPrice = 0;
			for (ConsumerRecord<String, String> record : records) {
				assertEquals("005930", record.key());
				if (partition >= 0) assertEquals(partition, record.partition());	//같은 종목은 같은 파티션
				partition = record.partition();
				JsonNode json = mapper.readTree(record.value());
				assertTrue(json.path("eventId").asText().startsWith("005930-"));
				assertTrue(json.path("price").asLong() > lastPrice);	//순서 유지
				lastPrice = json.path("price").asLong();
			}
			assertEquals(0, producer.failedCount());
			consumer.close();
			producer.stop();
		} finally {
			broker.destroy();
		}
	}
}
