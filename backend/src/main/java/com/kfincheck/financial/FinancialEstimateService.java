package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.kfincheck.client.KisApiClient;
import com.kfincheck.config.KisConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FinancialEstimateService {
    private final KisConfig config;
    private final KisApiClient auth;
    private final RestClient client;
    private final Map<String, EstimateFeed> cache = new HashMap<>();

    public FinancialEstimateService(KisConfig config, KisApiClient auth, RestClient.Builder builder) {
        this.config = config;
        this.auth = auth;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.client = builder.baseUrl(config.getBaseUrl()).requestFactory(factory).build();
    }

    public synchronized EstimateFeed latest(String stockCode) {
        FinancialService.company(stockCode);
        var saved = cache.get(stockCode);
        if (saved != null && saved.fetchedAt().plusSeconds(900).isAfter(Instant.now())) return saved;

        try {
            String token = auth.getAccessToken();
            auth.paceRequest();
            var response = client.get()
                    .uri(uri -> uri.path("/uapi/domestic-stock/v1/quotations/estimate-perform")
                            .queryParam("SHT_CD", stockCode).build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey", config.getAppKey()).header("appsecret", config.getAppSecret())
                    .header("tr_id", "HHKST668300C0").header("custtype", "P")
                    .retrieve().toEntity(JsonNode.class);
            String continuation = response.getHeaders().getFirst("tr_cont");
            if ("M".equals(continuation) || "F".equals(continuation)) throw unavailable();
            var feed = normalize(stockCode, response.getBody(), Instant.now());
            cache.put(stockCode, feed);
            return feed;
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
    }

    static EstimateFeed normalize(String stockCode, JsonNode body, Instant fetchedAt) {
        if (body == null || !"0".equals(body.path("rt_cd").asText())) throw unavailable();
        var summary = body.path("output1");
        if (summary.isArray()) summary = summary.path(0);
        var rows = body.path("output2");
        var periods = body.path("output4");
        if (!rows.isArray() || !periods.isArray()) throw unavailable();
        if (rows.isEmpty() && periods.isEmpty()) return new EstimateFeed(null, fetchedAt, List.of());
        if (rows.size() != 6 || periods.isEmpty() || periods.size() > 5
                || !("A" + stockCode).equals(summary.path("sht_cd").asText())) throw unavailable();

        List<Estimate> estimates = new ArrayList<>();
        for (int index = 0; index < periods.size(); index++) {
            String period = periods.path(index).path("dt").asText().strip();
            if (!period.matches("[0-9]{4}\\.(0[1-9]|1[0-2])E")) continue;
            String field = "data" + (index + 1);
            // KIS 추정손익계산서에서 금액 행과 증감률 행이 번갈아 나온다.
            var estimate = new Estimate(period.substring(0, 7),
                    amount(rows.path(0), field), amount(rows.path(2), field), amount(rows.path(4), field));
            if (estimate.revenue() != null || estimate.operatingProfit() != null || estimate.netIncome() != null) {
                estimates.add(estimate);
            }
        }
        return new EstimateFeed(parseDate(summary.path("estdate").asText()), fetchedAt, List.copyOf(estimates));
    }

    private static BigDecimal amount(JsonNode row, String field) {
        try { return new BigDecimal(row.path(field).asText().strip().replace(",", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static FinancialException unavailable() {
        return new FinancialException(HttpStatus.BAD_GATEWAY, "ESTIMATES_UNAVAILABLE",
                "KIS 예상 실적을 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    public record EstimateFeed(LocalDate estimateDate, Instant fetchedAt, List<Estimate> estimates) { }
    public record Estimate(String closingMonth, BigDecimal revenue, BigDecimal operatingProfit, BigDecimal netIncome) { }
}
