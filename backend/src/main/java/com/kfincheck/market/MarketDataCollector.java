package com.kfincheck.market;

import com.kfincheck.config.KisConfig;
import com.kfincheck.financial.DisclosureService;
import com.kfincheck.financial.OpenDartConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 표시 종목(company 테이블)의 일봉·공시를 미리 DB 에 쌓는다. 챗봇은 쌓인 DB 를 조회한다.
 * <ul>
 *   <li>서버 시작 30초 뒤: 일봉 최근 140일, 공시 최근 30일 (이미 있는 날은 덮어쓰기만 한다)</li>
 *   <li>평일 15:45: 그날 포함 최근 10일 일봉</li>
 *   <li>매일 19:30: 어제·오늘 공시</li>
 * </ul>
 * 실시간 프로필이 아니어도 돌도록 전용 스케줄러를 쓴다. 키가 없으면 해당 수집은 건너뛴다.
 * MARKET_DATA_COLLECT_ENABLED=false 이면 수집하지 않는다(조회할 때 없는 날짜는 그때 받아 저장한다).
 */
@Component
public class MarketDataCollector {
    private static final Logger log = LoggerFactory.getLogger(MarketDataCollector.class);

    private final JdbcTemplate jdbc;
    private final DailyPriceService prices;
    private final DisclosureService disclosures;
    private final KisConfig kisConfig;
    private final OpenDartConfig dartConfig;
    private final boolean enabled;
    private ThreadPoolTaskScheduler scheduler;

    public MarketDataCollector(JdbcTemplate jdbc, DailyPriceService prices, DisclosureService disclosures,
                               KisConfig kisConfig, OpenDartConfig dartConfig,
                               @Value("${market-data.collect.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.prices = prices;
        this.disclosures = disclosures;
        this.kisConfig = kisConfig;
        this.dartConfig = dartConfig;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("market-data-");
        scheduler.initialize();
        scheduler.schedule(this::backfill, java.time.Instant.now().plusSeconds(30));
        scheduler.schedule(this::dailyPrices, new CronTrigger("0 45 15 * * MON-FRI", DailyPriceService.KST));
        scheduler.schedule(this::dailyDisclosures, new CronTrigger("0 30 19 * * *", DailyPriceService.KST));
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdown();
    }

    void backfill() {
        LocalDate today = LocalDate.now(DailyPriceService.KST);
        collectPrices(today.minusDays(140), today);
        collectDisclosures(today.minusDays(29), today);
    }

    void dailyPrices() {
        LocalDate today = LocalDate.now(DailyPriceService.KST);
        collectPrices(today.minusDays(10), today);
    }

    void dailyDisclosures() {
        LocalDate today = LocalDate.now(DailyPriceService.KST);
        collectDisclosures(today.minusDays(1), today);
    }

    private void collectPrices(LocalDate from, LocalDate to) {
        if (!configured(kisConfig.getAppKey())) return;
        int total = 0;
        for (String stock : stocks()) {
            try {
                total += prices.collect(stock, from, to);
            } catch (RuntimeException error) {
                log.warn("일봉 저장 실패 ({}): {}", stock, error.getMessage());
            }
        }
        log.info("일봉 저장 {}건 ({} ~ {})", total, from, to);
    }

    private void collectDisclosures(LocalDate from, LocalDate to) {
        if (!configured(dartConfig.getApiKey())) return;
        for (String stock : stocks()) {
            try {
                disclosures.list(stock, from, to);
            } catch (RuntimeException error) {
                log.warn("공시 저장 실패 ({}): {}", stock, error.getMessage());
            }
        }
        log.info("공시 저장 완료 ({} ~ {})", from, to);
    }

    private List<String> stocks() {
        try {
            return jdbc.queryForList("SELECT stock_code FROM company ORDER BY stock_code", String.class);
        } catch (RuntimeException error) {
            log.warn("표시 종목을 읽지 못해 수집을 건너뜁니다: {}", error.getMessage());
            return List.of();
        }
    }

    private static boolean configured(String key) {
        return key != null && !key.isBlank() && !key.startsWith("YOUR_");
    }
}
