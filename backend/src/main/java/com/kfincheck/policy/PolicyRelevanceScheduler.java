package com.kfincheck.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PolicyRelevanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(PolicyRelevanceScheduler.class);
    private final PolicyRelevanceService service;
    private final AtomicBoolean running = new AtomicBoolean();

    public PolicyRelevanceScheduler(PolicyRelevanceService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${policy.relevance.interval-ms:300000}",
            initialDelayString = "${policy.relevance.initial-delay-ms:30000}")
    public void trigger() {
        if (!running.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("policy-relevance-analyzer").start(() -> {
            try {
                int count = service.analyzePending();
                if (count > 0) log.info("산업 정책 관련도 AI 분석 완료: {}건", count);
            } catch (RuntimeException error) {
                log.warn("산업 정책 관련도 AI 분석을 완료하지 못했습니다: {}", error.getClass().getSimpleName());
            } finally {
                running.set(false);
            }
        });
    }
}
