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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

@Service
public class DividendService {
    private final KisConfig config;
    private final KisApiClient auth;
    private final RestClient client;
    private final Map<String, DividendFeed> cache = new HashMap<>();

    public DividendService(KisConfig config, KisApiClient auth, RestClient.Builder builder) {
        this.config = config;
        this.auth = auth;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        client = builder.baseUrl(config.getBaseUrl()).requestFactory(factory).build();
    }

    public synchronized DividendFeed latest(String stockCode) {
        FinancialService.company(stockCode);
        LocalDate to = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var saved = cache.get(stockCode);
        if (saved != null && saved.to().equals(to) && saved.fetchedAt().plusSeconds(3600).isAfter(Instant.now())) {
            return saved;
        }

        LocalDate from = to.minusYears(3);
        Set<Dividend> dividends = new LinkedHashSet<>();
        try {
            String token = auth.getAccessToken();
            String continuation = "";
            for (int page = 0; page < 10; page++) {
                auth.paceRequest();
                var response = client.get().uri(uri -> uri.path("/uapi/domestic-stock/v1/ksdinfo/dividend")
                                .queryParam("CTS", "").queryParam("GB1", "0")
                                .queryParam("F_DT", from.format(DateTimeFormatter.BASIC_ISO_DATE))
                                .queryParam("T_DT", to.format(DateTimeFormatter.BASIC_ISO_DATE))
                                .queryParam("SHT_CD", stockCode).queryParam("HIGH_GB", "").build())
                        .header("authorization", "Bearer " + token)
                        .header("appkey", config.getAppKey()).header("appsecret", config.getAppSecret())
                        .header("tr_id", "HHKDB669102C0").header("custtype", "P")
                        .header("tr_cont", continuation).retrieve().toEntity(JsonNode.class);
                dividends.addAll(normalize(stockCode, response.getBody()));
                String next = response.getHeaders().getFirst("tr_cont");
                if (!"M".equals(next) && !"F".equals(next)) {
                    var entries = dividends.stream().sorted(Comparator.comparing(Dividend::recordDate).reversed()).toList();
                    var feed = new DividendFeed(from, to, Instant.now(), entries);
                    cache.put(stockCode, feed);
                    return feed;
                }
                continuation = "N";
            }
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
        throw unavailable();
    }

    static List<Dividend> normalize(String stockCode, JsonNode body) {
        if (body == null || !"0".equals(body.path("rt_cd").asText())) throw unavailable();
        var rows = body.path("output1");
        if (!rows.isArray()) throw unavailable();
        return StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> stockCode.equals(row.path("sht_cd").asText().strip()))
                .filter(row -> "보통".equals(row.path("stk_kind").asText().strip()))
                .map(row -> new Dividend(date(row, "record_date"), date(row, "divi_pay_dt"),
                        row.path("divi_kind").asText().strip(), amount(row)))
                .filter(row -> row.recordDate() != null && row.cashPerShare() != null && row.cashPerShare().signum() > 0)
                .toList();
    }

    private static BigDecimal amount(JsonNode row) {
        try { return new BigDecimal(row.path("per_sto_divi_amt").asText().strip().replace(",", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static LocalDate date(JsonNode row, String field) {
        String value = row.path(field).asText().strip();
        try {
            return value.contains("/") ? LocalDate.parse(value.replace('/', '-'))
                    : LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static FinancialException unavailable() {
        return new FinancialException(HttpStatus.BAD_GATEWAY, "DIVIDENDS_UNAVAILABLE",
                "배당 내역을 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    public record DividendFeed(LocalDate from, LocalDate to, Instant fetchedAt, List<Dividend> dividends) { }
    public record Dividend(LocalDate recordDate, LocalDate paymentDate, String kind, BigDecimal cashPerShare) { }
}
