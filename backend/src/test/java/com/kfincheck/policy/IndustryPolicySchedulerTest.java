package com.kfincheck.policy;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class IndustryPolicySchedulerTest {
    @Test
    void schedulesCollectionEveryThirtyMinutes() throws Exception {
        Scheduled annotation = IndustryPolicyScheduler.class.getMethod("trigger").getAnnotation(Scheduled.class);
        assertThat(annotation.fixedDelayString()).isEqualTo("${policy.collection.interval-ms:1800000}");
        assertThat(annotation.initialDelayString()).isEqualTo("${policy.collection.initial-delay-ms:15000}");
    }
}
