package com.kfincheck.chat;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 10분마다 24시간이 지난 대화 기록 속 기사 제목·요약을 지운다.
 * (대화를 열 때도 같은 정리를 먼저 하므로, 화면에는 24시간이 지난 기사 내용이 나오지 않는다.)
 */
@Component
public class NewsRetentionJob {
    private static final Logger LOG = LoggerFactory.getLogger(NewsRetentionJob.class);
    private final ChatHistory history;
    private ScheduledExecutorService timer;

    public NewsRetentionJob(ChatHistory history) {
        this.history = history;
    }

    @PostConstruct
    void start() {
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "news-retention");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::run, 1, 10, TimeUnit.MINUTES);
    }

    void run() {
        try {
            int changed = history.scrubExpiredNews(null);
            if (changed > 0) LOG.info("보관 기간이 지난 대화 속 기사 내용 {}건을 지웠습니다.", changed);
        } catch (RuntimeException error) {
            LOG.warn("대화 속 기사 내용 정리 실패: {}", error.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        if (timer != null) timer.shutdownNow();
    }
}
