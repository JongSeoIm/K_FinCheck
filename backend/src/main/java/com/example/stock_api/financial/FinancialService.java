package com.example.stock_api.financial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.example.stock_api.common.ApiException;
import com.example.stock_api.common.MarketHours;
import com.example.stock_api.dart.DartService;
import com.example.stock_api.stock.Stock;

import lombok.extern.slf4j.Slf4j;

/**
 * 실적 조회: 수집 후 1시간 이내의 자료는 DB 에서 재사용하고, 없거나 만료되면 OpenDART 에서 다시 받습니다.
 * 다시 받지 못해도 저장된 자료가 있으면 그것을 보여 주고 stale 로 표시합니다.
 */
@Slf4j
@Service
public class FinancialService {

    static final Duration FRESH_FOR = Duration.ofHours(1);

    private static final Map<String, String> REPORT_NAMES = Map.of("11011", "사업보고서");

    private final FinancialMapper mapper;
    private final FinancialStore store;
    private final DartService dart;

    private record Loaded(FinancialReport report, boolean stale) {}

    public FinancialService(FinancialMapper mapper, FinancialStore store, DartService dart) {
        this.mapper = mapper;
        this.store = store;
        this.dart = dart;
    }

    /** year 가 null 이면 최근 사업보고서 */
    public Map<String, Object> find(Stock stock, Integer year) {
        String code = stock.getCode();
        String corpCode = stock.getDartCorpCode();
        Loaded loaded = year == null
                ? load(code, mapper.findLatest(code), () -> dart.annualFinancials(corpCode), () -> mapper.findLatest(code))
                : load(code, mapper.findByYear(code, year), () -> dart.annualFinancials(corpCode, year), () -> mapper.findByYear(code, year));
        FinancialReport report = loaded.report();
        if (report == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_FINANCIALS",
                    year == null ? "최근 사업보고서 재무 데이터가 없습니다." : year + "년 사업보고서 재무 데이터가 없습니다.");
        }
        return response(stock, report, mapper.findValues(report.getReportId()), loaded.stale());
    }

    /** company 테이블에 없는 상장사: 외래 키 때문에 저장하지 않고 OpenDART 결과를 바로 돌려줍니다 */
    public Map<String, Object> findUnsaved(Stock stock, Integer year) {
        String corpCode = stock.getDartCorpCode();
        Map<String, Object> result = year == null ? dart.annualFinancials(corpCode) : dart.annualFinancials(corpCode, year);
        if (result == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_FINANCIALS",
                    year == null ? "최근 사업보고서 재무 데이터가 없습니다." : year + "년 사업보고서 재무 데이터가 없습니다.");
        }
        FinancialReport report = new FinancialReport();
        report.setStockCode(stock.getCode());
        report.setReceiptNo((String) result.get("receiptNo"));
        report.setBusinessYear((Integer) result.get("reportYear"));
        report.setReportCode((String) result.get("reportCode"));
        report.setFsDiv((String) result.get("fsDiv"));
        report.setCollectedAt(LocalDateTime.now(MarketHours.KST));
        return response(stock, report, FinancialStore.values(result), false);
    }

    private Loaded load(String stockCode, FinancialReport saved,
                        Supplier<Map<String, Object>> collect, Supplier<FinancialReport> reload) {
        if (saved != null && isFresh(saved.getCollectedAt(), LocalDateTime.now(MarketHours.KST))) {
            return new Loaded(saved, false);
        }
        try {
            Map<String, Object> result = collect.get();
            if (result == null) return new Loaded(saved, saved != null);
            store.save(stockCode, result);
            return new Loaded(reload.get(), false);
        } catch (ApiException | IllegalStateException | RestClientException e) {
            if (saved == null) throw e;
            log.warn("OpenDART 재수집 실패, 저장된 공시 사용: {}", e.getMessage());
            return new Loaded(saved, true);
        }
    }

    static boolean isFresh(LocalDateTime collectedAt, LocalDateTime now) {
        return collectedAt != null && Duration.between(collectedAt, now).compareTo(FRESH_FOR) < 0;
    }

    private static Map<String, Object> response(Stock stock, FinancialReport report,
                                                List<FinancialValue> values, boolean stale) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (FinancialValue value : values) {
            Long current = value.getCurrentAmount();
            Long previous = value.getPreviousAmount();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("accountCode", value.getAccountCode());
            item.put("accountName", value.getAccountName());
            item.put("currentAmount", current);
            item.put("previousAmount", previous);
            item.put("changeRate", changeRate(current, previous));
            item.put("comparison", comparison(current, previous));
            item.put("currency", value.getCurrency());
            item.put("sourceAccountId", value.getSourceAccountId());
            item.put("sourceAccountName", value.getSourceAccountName());
            item.put("valueStatus", value.getValueStatus());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", stock.getCode());
        result.put("companyName", stock.getName());
        result.put("businessYear", report.getBusinessYear());
        result.put("reportCode", report.getReportCode());
        result.put("reportName", REPORT_NAMES.getOrDefault(report.getReportCode(), report.getReportCode()));
        result.put("fsDiv", report.getFsDiv());
        result.put("basis", "CFS".equals(report.getFsDiv()) ? "연결" : "별도");
        result.put("receiptNo", report.getReceiptNo());
        result.put("url", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + report.getReceiptNo());
        result.put("source", "OpenDART");
        result.put("collectedAt", report.getCollectedAt());
        result.put("stale", stale);
        result.put("items", items);
        return result;
    }

    /** 전기 대비 상태. 전기가 0 이거나 금액이 없으면 비교하지 않습니다. */
    static String comparison(Long current, Long previous) {
        if (current == null || previous == null) return "NOT_COMPARABLE";
        if (previous > 0) return current < 0 ? "TURN_LOSS" : "NORMAL";
        if (previous < 0 && current > 0) return "TURN_PROFIT";
        if (previous < 0 && current < 0) return "LOSS_CONTINUE";
        return "NOT_COMPARABLE";
    }

    /** 전년 대비 증감률(%). 전기·당기가 모두 흑자 범위일 때만 계산합니다. */
    static Double changeRate(Long current, Long previous) {
        if (!"NORMAL".equals(comparison(current, previous))) return null;
        return BigDecimal.valueOf(current - previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}