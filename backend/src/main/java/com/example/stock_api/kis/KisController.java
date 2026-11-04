package com.example.stock_api.kis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.common.ApiException;
import com.example.stock_api.dart.DartService;
import com.example.stock_api.financial.FinancialService;
import com.example.stock_api.news.NaverNewsService;
import com.example.stock_api.realtime.BarAggregator;
import com.example.stock_api.realtime.QuoteHub;
import com.example.stock_api.stock.Stock;
import com.example.stock_api.stock.StockService;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 프런트엔드가 쓰는 모든 REST API. 브라우저는 Next.js(3000)의 /api/kis/* 로 요청하고 rewrites 로 여기(8080)에 전달됩니다.
 * 오류 응답은 {"code": "...", "error": "..."} (ApiExceptionHandler).
 */
@Validated
@RestController
@RequestMapping("/api/kis")
public class KisController {

    private static final String CODE = "\\d{6}";

    private final KisService kis;
    private final StockService stocks;
    private final DartService dart;
    private final NaverNewsService naver;
    private final QuoteHub hub;
    private final FinancialService financials;

    public KisController(KisService kis, StockService stocks, DartService dart,
                         NaverNewsService naver, QuoteHub hub, FinancialService financials) {
        this.kis = kis;
        this.stocks = stocks;
        this.dart = dart;
        this.naver = naver;
        this.hub = hub;
        this.financials = financials;
    }

    // ───── 지원 종목 (DB) ─────

    @GetMapping("/stocks")
    public List<Stock> stocks() {
        return stocks.all();
    }

    @GetMapping("/stocks/search")
    public List<Stock> search(@RequestParam(defaultValue = "") @Size(max = 30) String q) {
        return stocks.search(q);
    }

    @GetMapping("/stocks/{code}")
    public Stock stock(@PathVariable @Pattern(regexp = CODE) String code) {
        return supported(code);
    }

    // ───── 시세 · 차트 (KIS) ─────

    @GetMapping("/price/{code}")
    public Map<String, Object> price(@PathVariable @Pattern(regexp = CODE) String code) {
        supported(code);
        return kis.currentPrice(code);
    }

    /**
     * 여러 종목 한 번에 (Polling 용): /api/kis/prices?codes=005930,000660
     * 서버가 실시간·주기 조회로 이미 받아 둔 시세를 돌려주고, 아직 없는 종목만 KIS 를 호출합니다.
     */
    @GetMapping("/prices")
    public Map<String, Object> prices(@RequestParam List<@Pattern(regexp = CODE) String> codes) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String code : codes.stream().distinct().limit(40).toList()) {
            try {
                supported(code);
                Map<String, Object> cached = hub.latest(code);
                result.put(code, cached != null ? cached : kis.currentPrice(code));
            } catch (ApiException e) {
                result.put(code, Map.of("code", e.getCode(), "error", e.getMessage()));
            } catch (RuntimeException e) {
                result.put(code, Map.of("code", "UPSTREAM_ERROR", "error", String.valueOf(e.getMessage())));
            }
        }
        return result;
    }

    /** 1D = 당일 1분봉, 1W·1M·1Y = 일봉 OHLC */
    @GetMapping("/candles/{code}")
    public List<Map<String, Object>> candles(@PathVariable @Pattern(regexp = CODE) String code,
                                             @RequestParam(defaultValue = "1D") @Pattern(regexp = "1D|1W|1M|1Y") String range) {
        supported(code);
        return "1D".equals(range) ? kis.minuteCandles(code) : kis.dailyCandles(code, range);
    }

    /** 일·주·월·년봉 (KIS 기간별 조회): period = D | W | M | Y */
    @GetMapping("/period-candles/{code}")
    public List<Map<String, Object>> periodCandles(@PathVariable @Pattern(regexp = CODE) String code,
                                                   @RequestParam @Pattern(regexp = "D|W|M|Y") String period) {
        supported(code);
        return kis.periodCandles(code, period);
    }

    /** 초·분봉 (서버 체결 집계): interval = 봉 하나의 길이(초) */
    @GetMapping("/bars/{code}")
    public List<BarAggregator.Bar> bars(@PathVariable @Pattern(regexp = CODE) String code, @RequestParam int interval) {
        supported(code);
        if (!BarAggregator.INTERVALS.contains(interval)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INTERVAL", "지원하지 않는 차트 간격입니다.");
        }
        return hub.bars(code, interval);
    }

    /** 종목 이름·업종(DB) + 시가총액(KIS) */
    @GetMapping("/profile/{code}")
    public Map<String, Object> profile(@PathVariable @Pattern(regexp = CODE) String code) {
        Stock s = supported(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", s.getCode());
        result.put("name", s.getName());
        result.put("exchange", s.getMarket());
        result.put("industry", s.getIndustry());
        try {
            result.put("marketCapitalization", kis.currentPrice(code).get("marketCapitalization"));
        } catch (RuntimeException e) {
            result.put("marketCapitalization", null);
        }
        return result;
    }

    // ───── 뉴스 (네이버) · 투자의견 (KIS) · 실적 (OpenDART) ─────

    /** 관련 기사: 네이버 뉴스 검색 (기업명, 최신순 최대 10개) */
    @GetMapping("/news/{code}")
    public List<Map<String, Object>> news(@PathVariable @Pattern(regexp = CODE) String code) {
        return naver.search(supported(code).getName());
    }

    @GetMapping("/opinion/{code}")
    public List<Map<String, Object>> opinion(@PathVariable @Pattern(regexp = CODE) String code) {
        supported(code);
        return kis.investOpinion(code);
    }

    /** 최근 사업보고서 실적: DB 저장본을 쓰고, 수집 후 1시간이 지나면 다시 받습니다 */
    @GetMapping("/financials/{code}")
    public Map<String, Object> financials(@PathVariable @Pattern(regexp = CODE) String code) {
        return financials.find(supported(code), null);
    }

    // ───── 상태 ─────

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>(hub.status());
        s.put("db", stocks.dbAlive() ? "ok" : "error");
        s.put("dartConfigured", dart.isConfigured());
        s.put("naverConfigured", naver.isConfigured());
        return s;
    }

    private Stock supported(String code) {
        return stocks.find(code).orElseThrow(() -> ApiException.notSupported(code));
    }
}