package com.example.stock_api.realtime;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * 개발용: Docker 없이 같은 프로세스 안에 진짜 Kafka 브로커를 띄우고,
 * KIS 실시간 체결(stream.enabled=true)을 켠 채로 백엔드를 실행합니다. 서버를 끄면 Kafka 내용은 사라집니다.
 */
public class LocalRealtimeApplication {

    public static void main(String[] args) throws Exception {
        var broker = new EmbeddedKafkaKraftBroker(1, 1, "stock-quotes-v1");
        broker.afterPropertiesSet();
        try {
            // @SpringBootApplication 클래스를 찾아 그대로 실행합니다. (클래스 이름을 몰라도 됩니다.)
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootApplication.class));
            var found = scanner.findCandidateComponents("com.example.stock_api");
            if (found.isEmpty()) {
                throw new IllegalStateException("@SpringBootApplication 클래스를 찾지 못했습니다.");
            }
            Class<?> main = Class.forName(found.iterator().next().getBeanClassName());

            // 기본값(가장 낮은 우선순위)이므로 application-local.properties 의 같은 설정이 있으면 그쪽이 우선합니다.
            Map<String, Object> defaults = new HashMap<>();
            defaults.put("stream.enabled", "true");
            defaults.put("spring.kafka.bootstrap-servers", broker.getBrokersAsString());
            defaults.put("spring.kafka.consumer.group-id", "k-fincheck-quotes");
            defaults.put("spring.kafka.consumer.auto-offset-reset", "latest");
            defaults.put("spring.kafka.consumer.enable-auto-commit", "false");
            defaults.put("spring.kafka.listener.ack-mode", "record");
            defaults.put("spring.kafka.producer.acks", "all");
            defaults.put("spring.kafka.producer.properties.enable.idempotence", "true");
            defaults.put("spring.kafka.producer.properties.max.block.ms", "3000");
            defaults.put("spring.kafka.producer.properties.delivery.timeout.ms", "10000");
            defaults.put("spring.kafka.producer.properties.request.timeout.ms", "3000");
            // 연결 유지 작업이 몇 초 걸려도 시세 수집·Push 전송이 막히지 않게 스레드를 늘립니다.
            defaults.put("spring.task.scheduling.pool.size", "4");

            SpringApplication app = new SpringApplication(main);
            app.setDefaultProperties(defaults);
            var context = app.run(args);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                context.close();
                broker.destroy();
            }));
        } catch (RuntimeException e) {
            broker.destroy();
            throw e;
        }
    }
}
