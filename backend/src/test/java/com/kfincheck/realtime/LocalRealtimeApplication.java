package com.kfincheck.realtime;

import com.kfincheck.KFinCheckApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Local development only: runs a real in-process Kafka broker without Docker. */
public class LocalRealtimeApplication {
    public static void main(String[] args) {
        var broker = new EmbeddedKafkaKraftBroker(1, 1, "stock-quotes-v1");
        broker.afterPropertiesSet();
        try {
            List<String> options = new ArrayList<>(List.of("--spring.profiles.active=realtime",
                    "--spring.kafka.bootstrap-servers=" + broker.getBrokersAsString()));
            options.addAll(Arrays.asList(args));
            var context = SpringApplication.run(KFinCheckApplication.class, options.toArray(String[]::new));
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
