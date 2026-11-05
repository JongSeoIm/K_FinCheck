package com.example.stock_api.tool;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.common.ApiException;
import com.example.stock_api.common.MarketHours;
import com.example.stock_api.dart.ListedCompanies;
import com.example.stock_api.financial.FinancialService;
import com.example.stock_api.kis.KisService;
import com.example.stock_api.news.NaverNewsService;
import com.example.stock_api.realtime.QuoteStore;
import com.example.stock_api.stock.Stock;
import com.example.stock_api.stock.StockService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** AI 서버(FastAPI)가 쓰는 조회 API. 공개 화면과 같은 읽기 전용 자료만 제공합니다. */
@Validated
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private static final String CODE = "\\d{6}";

    private final StockService stocks;
    private final FinancialService financials;
    private final KisService kis;
    private final NaverNewsService naver;
    private final QuoteStore hub;
    private final ListedCompanies listed;

    public ToolController(StockService stocks, FinancialService financials, KisService kis,
                          NaverNewsService naver, QuoteStore hub, ListedCompanies listed) {
        this.stocks = stocks;
        this.financials = financials;
        this.kis = kis;
        this.naver = naver;
        this.hub = hub;
        this.listed = listed;
    }

    /** 질문에서 기업을 찾기 위한 지원 기업 목록 */
    @GetMapping("/companies")
    public List<Map<String, Object>> companies() {
        return stocks.all().stream().map(ToolController::company).toList();
    }

    /** get_company_info */
    @GetMapping("/company")
    public List<Map<String, Object>> company(@RequestParam @Size(min = 1, max = 30) String query) {
        List<Stock> found = stocks.search(query);
        if (found.isEmpty()) {
            found = listed.findByName(query).map(List::of).orElse(List.of());
        }
        return found.stream().map(ToolController::company).toList();
    }

    private static Map<String, Object> company(Stock stock) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stockCode", stock.getCode());
        item.put("companyName", stock.getName());
        item.put("market", stock.getMarket());
        item.put("industry", stock.getIndustry());
        return item;
    }

    /** get_stock_quote: 장중 실시간 체결이 있으면 그 값을, 없거나 장외이면 KIS REST 현재가를 씁니다. */
    @GetMapping("/quote/{code}")
    public Map<String, Object> quote(@PathVariable @Pattern(regexp = CODE) String code) {
        Stock stock = supported(code);
        Map<String, Object> live = hub.latest(code);
        boolean marketOpen = MarketHours.isOpen();
        boolean realtime = marketOpen && live != null && "realtime".equals(live.get("source"));
        Map<String, Object> quote = realtime ? live : kis.currentPrice(code);

        Object tradeTime = realtime ? live.get("tradeTime") : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);
        result.put("companyName", stock.getName());
        result.put("price", quote.get("price"));
        result.put("priceChange", quote.get("priceChange"));
        result.put("changeRate", quote.get("change"));
        result.put("volume", quote.get("volume"));
        result.put("provider", "KIS");
        result.put("dataSource", realtime ? "realtime" : "rest");
        result.put("marketOpen", marketOpen);
        result.put("tradeTime", tradeTime);
        // REST 현재가 응답에는 체결 시각이 없어 조회 시각을 기준 시각으로 표시합니다
        result.put("basisAt", realtime ? LocalDate.now(MarketHours.KST) + "T" + tradeTime + "+09:00" : quote.get("updatedAt"));
        result.put("basisType", realtime ? "체결 시각" : "조회 시각");
        return result;
    }

    /** get_financial_result: year 를 생략하면 최근 사업보고서 */
    @GetMapping("/financial/{code}")
    public Map<String, Object> financial(@PathVariable @Pattern(regexp = CODE) String code,
                                         @RequestParam(required = false) @Min(2015) @Max(2100) Integer year) {
        Stock stock = supported(code);
        return stocks.find(code).isPresent() ? financials.find(stock, year) : financials.findUnsaved(stock, year);
    }

    /** get_company_news */
    @GetMapping("/news/{code}")
    public Map<String, Object> news(@PathVariable @Pattern(regexp = CODE) String code) {
        Stock stock = supported(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);
        result.put("companyName", stock.getName());
        result.put("provider", naver.providerName());
        result.put("articles", naver.search(stock.getName()));
        return result;
    }

    private Stock supported(String code) {
        return stocks.find(code).or(() -> listed.findByCode(code)).orElseThrow(() -> ApiException.notSupported(code));
    }
}