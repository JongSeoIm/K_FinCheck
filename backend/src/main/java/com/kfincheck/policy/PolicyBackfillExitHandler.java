package com.kfincheck.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "policy.collection.exit-when-backfill-complete", havingValue = "true")
public class PolicyBackfillExitHandler {
    private static final Logger log = LoggerFactory.getLogger(PolicyBackfillExitHandler.class);
    private final ConfigurableApplicationContext context;

    public PolicyBackfillExitHandler(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @EventListener
    public void exit(PolicyBackfillCompletedEvent ignored) {
        log.info("정책브리핑 3개월 백필이 완료되어 전용 백엔드를 종료합니다.");
        int exitCode = SpringApplication.exit(context, () -> 0);
        System.exit(exitCode);
    }
}
