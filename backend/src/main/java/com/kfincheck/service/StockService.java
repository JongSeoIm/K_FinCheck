package com.kfincheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kfincheck.client.KisApiClient;
import com.kfincheck.client.dto.KisDailyChartResponse;
import com.kfincheck.client.dto.KisPriceResponse;
import com.kfincheck.dto.StockCandleDto;
import com.kfincheck.dto.StockPriceDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StockService {

    private static final DateTimeFormatter KIS_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long PRICE_CACHE_TTL_MS = 3_000L;
    private static final long CANDLE_CACHE_TTL_MS = 25_000L;
    private static final long INVESTOR_CACHE_TTL_MS = 60_000L;
    private static final int INVESTOR_DAYS = 5;

    private static class CachedPrice {
        private final StockPriceDto data;
        private final long expiryEpochMs;

        CachedPrice(StockPriceDto data, long expiryEpochMs) {
            this.data = data;
            this.expiryEpochMs = expiryEpochMs;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiryEpochMs;
        }

        StockPriceDto getData() {
            return data;
        }
    }

    private static class CachedCandles {
        private final List<StockCandleDto> data;
        private final long expiryEpochMs;

        CachedCandles(List<StockCandleDto> data, long expiryEpochMs) {
            this.data = data;
            this.expiryEpochMs = expiryEpochMs;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiryEpochMs;
        }

        List<StockCandleDto> getData() {
            return data;
        }
    }

    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();
    private final Map<String, CachedCandles> candleCache = new ConcurrentHashMap<>();
    private final Map<String, CachedInvestorTrades> investorCache = new ConcurrentHashMap<>();
    private volatile InvestorDateBackoff investorDateBackoff;
    private static final Map<String, String> KNOWN_STOCKS = Map.of(
            "005930", "삼성전자",
            "000660", "SK하이닉스",
            "035420", "NAVER",
            "035720", "카카오",
            "005380", "현대차",
            "000270", "기아",
            "051910", "LG화학",
            "006400", "삼성SDI"
    );

    private final KisApiClient kisApiClient;

    public StockService(KisApiClient kisApiClient) {
        this.kisApiClient = kisApiClient;
    }

    public StockPriceDto getStockPrice(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("종목코드가 올바르지 않습니다.");
        }

        String normalizedCode = stockCode.trim();
        CachedPrice cached = priceCache.get(normalizedCode);
        if (cached != null && cached.isValid()) {
            return cached.getData();
        }

        KisPriceResponse response = kisApiClient.getStockPrice(normalizedCode);

        if (response == null || !response.isSuccess() || response.getOutput() == null) {
            String errorMessage = response != null ? response.getMsg1() : "응답 데이터 없음";
            throw new IllegalStateException("KIS 시세 조회 실패: " + errorMessage);
        }

        KisPriceResponse.PriceOutput out = response.getOutput();
        String stockName = KNOWN_STOCKS.getOrDefault(normalizedCode, out.getRprsMrktKorName() != null ? out.getRprsMrktKorName() : normalizedCode);

        StockPriceDto dto = new StockPriceDto(
                normalizedCode,
                stockName,
                parseLong(out.getStckPrpr()),
                parseLong(out.getPrdyVrss()),
                parseDouble(out.getPrdyCtrt()),
                parseLong(out.getAcmlVol()),
                parseLong(out.getStckOprc()),
                parseLong(out.getStckHgpr()),
                parseLong(out.getStckLwpr()),
                out.getRprsMrktKorName(),
                "KIS"
        );
        priceCache.put(normalizedCode, new CachedPrice(dto, System.currentTimeMillis() + PRICE_CACHE_TTL_MS));
        return dto;
    }

    public List<StockCandleDto> getDailyCandles(String stockCode, int days) {
        return getDailyCandles(stockCode, days, "D");
    }

    public List<StockCandleDto> getDailyCandles(String stockCode, int days, String periodType) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("종목코드가 올바르지 않습니다.");
        }

        String normalizedCode = stockCode.trim();
        String period = (periodType != null && !periodType.isBlank()) ? periodType.trim().toUpperCase() : "D";
        String cacheKey = normalizedCode + ":" + period + ":" + days;

        CachedCandles cached = candleCache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            return cached.getData();
        }

        LocalDate endLocalDate = LocalDate.now();
        LocalDate startLocalDate;
        int requestedLimit;

        switch (period) {
            case "W" -> {
                requestedLimit = 52;
                startLocalDate = endLocalDate.minusWeeks(60);
            }
            case "M" -> {
                requestedLimit = 36;
                startLocalDate = endLocalDate.minusMonths(48);
            }
            case "Y" -> {
                requestedLimit = 15;
                startLocalDate = endLocalDate.minusYears(15);
            }
            default -> {
                period = "D";
                requestedLimit = days > 0 ? Math.min(days, 100) : 40;
                startLocalDate = endLocalDate.minusDays((long) requestedLimit * 2L);
            }
        }

        String startDate = startLocalDate.format(KIS_DATE_FORMATTER);
        String endDate = endLocalDate.format(KIS_DATE_FORMATTER);

        KisDailyChartResponse response = kisApiClient.getDailyCandles(normalizedCode, startDate, endDate, period);

        if (response == null || !response.isSuccess() || response.getOutput2() == null) {
            String errorMessage = response != null ? response.getMsg1() : "시세 데이터 없음";
            throw new IllegalStateException("KIS 시세 조회 실패: " + errorMessage);
        }

        List<StockCandleDto> candles = new ArrayList<>();
        List<KisDailyChartResponse.DailyCandleOutput> rawList = response.getOutput2();

        int limit = Math.min(rawList.size(), requestedLimit);
        for (int i = 0; i < limit; i++) {
            KisDailyChartResponse.DailyCandleOutput item = rawList.get(i);
            String rawDate = item.getStckBsopDate();
            String formattedDate = formatDate(rawDate);

            candles.add(new StockCandleDto(
                    formattedDate,
                    parseLong(item.getStckOprc()),
                    parseLong(item.getStckHgpr()),
                    parseLong(item.getStckLwpr()),
                    parseLong(item.getStckClpr()),
                    parseLong(item.getAcmlVol())
            ));
        }

        Collections.reverse(candles);
        candleCache.put(cacheKey, new CachedCandles(candles, System.currentTimeMillis() + CANDLE_CACHE_TTL_MS));
        return candles;
    }

    public List<InvestorTrade> getInvestorTrades(String stockCode) {
        if (stockCode == null || !KNOWN_STOCKS.containsKey(stockCode)) {
            throw new IllegalArgumentException("지원하지 않는 종목입니다.");
        }
        return getInvestorDays(stockCode, LocalDate.now(ZoneId.of("Asia/Seoul"))).reversed().stream()
                .map(InvestorDay::trade)
                .filter(InvestorTrade::hasQuantity)
                .limit(INVESTOR_DAYS)
                .toList().reversed();
    }

    public InvestorRanking getInvestorRanking() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var rowsByDate = new TreeMap<LocalDate, List<InvestorAmounts>>();
        for (String code : KNOWN_STOCKS.keySet().stream().sorted().toList()) {
            for (InvestorDay day : getInvestorDays(code, today)) {
                if (day.amounts().isComplete()) {
                    rowsByDate.computeIfAbsent(day.trade().date(), ignored -> new ArrayList<>()).add(day.amounts());
                }
            }
        }
        return rowsByDate.descendingMap().entrySet().stream()
                .filter(entry -> entry.getValue().size() == KNOWN_STOCKS.size())
                .findFirst()
                .map(entry -> new InvestorRanking(entry.getKey(), List.copyOf(entry.getValue())))
                .orElseGet(() -> new InvestorRanking(null, List.of()));
    }

    private List<InvestorDay> getInvestorDays(String stockCode, LocalDate today) {
        return investorCache.compute(stockCode, (code, cached) -> {
            if (cached != null && cached.date().equals(today) && cached.expiresAt() > System.currentTimeMillis()) {
                return cached;
            }
            return new CachedInvestorTrades(today, loadInvestorTrades(code, today),
                    System.currentTimeMillis() + INVESTOR_CACHE_TTL_MS);
        }).days();
    }

    private List<InvestorDay> loadInvestorTrades(String stockCode, LocalDate today) {
        var days = new TreeMap<LocalDate, InvestorDay>();
        InvestorDateBackoff backoff = investorDateBackoff;
        LocalDate dateToRequest = backoff != null && backoff.appliesTo(today) ? today.minusDays(1) : today;
        String continuation = "";
        for (int page = 0; page < 10; page++) {
            var response = kisApiClient.getInvestorTrades(stockCode, dateToRequest.format(KIS_DATE_FORMATTER), continuation);
            JsonNode body = response.getBody();
            if (page == 0 && dateToRequest.equals(today) && body != null && !"0".equals(body.path("rt_cd").asText())
                    && "OPSQ2001".equals(body.path("msg_cd").asText())) {
                // KIS can reject today's date before the daily investor data is published.
                investorDateBackoff = new InvestorDateBackoff(today, System.currentTimeMillis() + INVESTOR_CACHE_TTL_MS);
                dateToRequest = today.minusDays(1);
                response = kisApiClient.getInvestorTrades(stockCode, dateToRequest.format(KIS_DATE_FORMATTER), continuation);
                body = response.getBody();
            }
            if (body == null || !"0".equals(body.path("rt_cd").asText()) || !body.path("output2").isArray()) {
                throw new IllegalStateException("KIS 투자자별 매매 동향 응답이 올바르지 않습니다.");
            }
            for (JsonNode row : body.path("output2")) {
                LocalDate date;
                try { date = LocalDate.parse(row.path("stck_bsop_date").asText().strip(), DateTimeFormatter.BASIC_ISO_DATE); }
                catch (DateTimeParseException e) { continue; }
                if (date.isAfter(dateToRequest)) continue;
                var trade = new InvestorTrade(date, investorNumber(row, "prsn_ntby_qty"),
                        investorNumber(row, "orgn_ntby_qty"), investorNumber(row, "frgn_ntby_qty"));
                var amounts = new InvestorAmounts(stockCode, investorNumber(row, "prsn_ntby_tr_pbmn"),
                        investorNumber(row, "orgn_ntby_tr_pbmn"), investorNumber(row, "frgn_ntby_tr_pbmn"));
                if (trade.hasQuantity() || amounts.isComplete()) {
                    days.putIfAbsent(date, new InvestorDay(trade, amounts));
                }
            }
            if (days.values().stream().filter(day -> day.trade().hasQuantity()).count() >= INVESTOR_DAYS) break;
            String next = response.getHeaders().getFirst("tr_cont");
            if (!"M".equals(next) && !"F".equals(next)) break;
            continuation = "N";
        }
        return List.copyOf(days.values());
    }

    private static Long investorNumber(JsonNode row, String field) {
        try { return Long.valueOf(row.path(field).asText().strip()); }
        catch (NumberFormatException e) { return null; }
    }

    private record CachedInvestorTrades(LocalDate date, List<InvestorDay> days, long expiresAt) { }

    private record InvestorDateBackoff(LocalDate date, long expiresAt) {
        private boolean appliesTo(LocalDate today) {
            return date.equals(today) && expiresAt > System.currentTimeMillis();
        }
    }

    private record InvestorDay(InvestorTrade trade, InvestorAmounts amounts) { }

    public record InvestorTrade(LocalDate date, Long individual, Long institution, Long foreign) {
        private boolean hasQuantity() {
            return individual != null || institution != null || foreign != null;
        }
    }

    public record InvestorRanking(LocalDate date, List<InvestorAmounts> stocks) { }

    public record InvestorAmounts(String symbol, Long individualMillions, Long institutionMillions, Long foreignMillions) {
        private boolean isComplete() {
            return individualMillions != null && institutionMillions != null && foreignMillions != null;
        }
    }

    private String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
