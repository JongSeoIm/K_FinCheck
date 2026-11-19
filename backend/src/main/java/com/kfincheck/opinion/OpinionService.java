package com.kfincheck.opinion;

import com.fasterxml.jackson.databind.JsonNode;
import com.kfincheck.client.KisApiClient;
import com.kfincheck.config.KisConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;

@Service
public class OpinionService {
    private final KisConfig config;
    private final KisApiClient authentication;
    private final RestClient client;
    private final Map<String, OpinionFeed> cache = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public OpinionService(KisConfig config, KisApiClient authentication, RestClient.Builder builder) {
        this(config, authentication, configuredClient(config, builder));
    }

    OpinionService(KisConfig config, KisApiClient authentication, RestClient client) {
        this.config = config;
        this.authentication = authentication;
        this.client = client;
    }

    private static RestClient configuredClient(KisConfig config, RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return builder.baseUrl(config.getBaseUrl()).requestFactory(factory).build();
    }

    public synchronized OpinionFeed latest(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9]{6}")) {
            throw new OpinionException(HttpStatus.BAD_REQUEST, "INVALID_STOCK", "종목 코드는 숫자 6자리로 입력해 주세요.");
        }
        var cached = cache.get(stockCode);
        if (cached != null && cached.periodEnd().equals(LocalDate.now(ZoneId.of("Asia/Seoul")))
                && cached.fetchedAt().plusSeconds(900).isAfter(Instant.now())) return cached;
        LocalDate end = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate start = end.minusMonths(3);
        JsonNode body;
        boolean partial;
        try {
            String token = authentication.getAccessToken();
            var response = client.get().uri(uri -> uri.path("/uapi/domestic-stock/v1/quotations/invest-opinion")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_COND_SCR_DIV_CODE", "16633")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .queryParam("FID_INPUT_DATE_1", start.format(DateTimeFormatter.BASIC_ISO_DATE))
                            .queryParam("FID_INPUT_DATE_2", end.format(DateTimeFormatter.BASIC_ISO_DATE)).build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey", config.getAppKey()).header("appsecret", config.getAppSecret())
                    .header("tr_id", "FHKST663300C0").header("custtype", "P")
                    .retrieve().toEntity(JsonNode.class);
            body = response.getBody();
            String continuation = response.getHeaders().getFirst("tr_cont");
            partial = "M".equals(continuation) || "F".equals(continuation);
        } catch (Exception error) {
            // 인증값이나 외부 응답 원문을 브라우저·로그로 전달하지 않는다.
            throw unavailable();
        }
        if (body == null || !"0".equals(body.path("rt_cd").asText()) || !body.path("output").isArray()) throw unavailable();
        List<Opinion> items = new ArrayList<>();
        for (JsonNode row : body.path("output")) {
            LocalDate date;
            try {
                date = LocalDate.parse(row.path("stck_bsop_date").asText(), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException error) { continue; }
            if (date.isBefore(start) || date.isAfter(end)) continue;
            String opinion = normalizeOpinion(row.path("invt_opnn").asText(""));
            BigDecimal target = amount(row.path("hts_goal_prc").asText());
            if (opinion.isBlank() && target == null) continue;
            String broker = row.path("mbcr_name").asText("").trim();
            items.add(new Opinion(date, broker.isBlank() ? null : broker, opinion.isBlank() ? null : opinion, target));
        }
        Map<String, Opinion> latestByBroker = new TreeMap<>();
        int missingBrokerRows = 0;
        for (Opinion item : items) {
            if (item.broker() == null) { missingBrokerRows++; continue; }
            latestByBroker.merge(item.broker(), item, (previous, next) -> {
                if (!previous.date().equals(next.date())) return previous.date().isAfter(next.date()) ? previous : next;
                if (!Objects.equals(previous.opinion(), next.opinion())) {
                    return new Opinion(previous.date(), previous.broker(), "동일 날짜 의견 확인 필요", null);
                }
                // 날짜·의견이 같고 목표주가만 다른 경우 가격은 단정하지 않는다.
                return Objects.equals(previous.targetPrice(), next.targetPrice()) ? previous
                        : new Opinion(previous.date(), previous.broker(), previous.opinion(), null);
            });
        }
        var latest = latestByBroker.values().stream()
                .sorted(Comparator.comparing(Opinion::date).reversed().thenComparing(Opinion::broker)).toList();
        int counted = (int) latest.stream().filter(item -> category(item.opinion()) != null).count();
        List<Distribution> distribution = new ArrayList<>();
        for (String label : List.of("매수", "중립", "매도")) {
            int count = (int) latest.stream().filter(item -> label.equals(category(item.opinion()))).count();
            BigDecimal percentage = counted == 0 ? null : BigDecimal.valueOf(count * 100L)
                    .divide(BigDecimal.valueOf(counted), 1, java.math.RoundingMode.HALF_UP);
            distribution.add(new Distribution(label, count, percentage));
        }
        var feed = new OpinionFeed(stockCode, "KIS", start, end, Instant.now(), latest,
                distribution, counted, latest.size() - counted, missingBrokerRows, partial);
        if (cache.size() >= 256) cache.clear();
        cache.put(stockCode, feed);
        return feed;
    }

    private static BigDecimal amount(String value) {
        try {
            BigDecimal result = new BigDecimal(value.trim().replace(",", ""));
            return result.signum() > 0 ? result : null;
        } catch (NumberFormatException error) { return null; }
    }

    private static String normalizeOpinion(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "BUY" -> "매수";
            case "SELL" -> "매도";
            case "HOLD", "NEUTRAL" -> "중립";
            default -> value.trim();
        };
    }

    private static String category(String opinion) {
        return Set.of("매수", "중립", "매도").contains(opinion == null ? "" : opinion) ? opinion : null;
    }

    private static OpinionException unavailable() {
        return new OpinionException(HttpStatus.SERVICE_UNAVAILABLE, "OPINION_UNAVAILABLE",
                "투자의견 자료를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    public record Opinion(LocalDate date, String broker, String opinion, BigDecimal targetPrice) {}
    public record Distribution(String label, int count, BigDecimal percentage) {}
    public record OpinionFeed(String stockCode, String source, LocalDate periodStart, LocalDate periodEnd,
                              Instant fetchedAt, List<Opinion> items, List<Distribution> distribution,
                              int countedBrokers, int excludedBrokers, int missingBrokerRows, boolean partial) {}
    public static class OpinionException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        public OpinionException(HttpStatus status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
