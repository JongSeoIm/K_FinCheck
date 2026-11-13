package com.kfincheck.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class IndustryPolicyScheduler {
    private static final Logger log = LoggerFactory.getLogger(IndustryPolicyScheduler.class);
    private final IndustryPolicyService service;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean();

    public IndustryPolicyScheduler(IndustryPolicyService service, ApplicationEventPublisher events) {
        this.service = service;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${policy.collection.interval-ms:1800000}",
            initialDelayString = "${policy.collection.initial-delay-ms:15000}")
    public void trigger() {
        if (!running.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("industry-policy-collector").start(() -> {
            try {
                IndustryPolicyService.CollectionSummary result = service.collectScheduled();
                if (result.backfillCompleted()) {
                    events.publishEvent(new PolicyBackfillCompletedEvent());
                    return;
                }
                if (!result.errors().isEmpty()) {
                    log.warn("산업 정책 수집 일부 실패: {}", result.errors());
                } else if (result.motirCollected() || result.briefingLatestCollected()
                        || result.backfillPage() != null) {
                    log.info("산업 정책 수집 완료: MOTIR={}, 정책브리핑 최신={}, 백필 페이지={}, 백필 완료={}",
                            result.motirCollected(), result.briefingLatestCollected(), result.backfillPage(),
                            result.backfillCompleted());
                }
            } catch (RuntimeException error) {
                log.warn("산업 정책 백그라운드 수집을 시작하지 못했습니다: {}",
                        error.getClass().getSimpleName());
            } finally {
                running.set(false);
            }
        });
    }
}
