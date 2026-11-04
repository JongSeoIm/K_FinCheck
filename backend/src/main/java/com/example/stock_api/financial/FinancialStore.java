package com.example.stock_api.financial;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.stock_api.common.MarketHours;
import com.example.stock_api.dart.DartService;

/** OpenDART 조회 결과를 공시 1건 + 계정 3건으로 저장합니다. */
@Service
public class FinancialStore {

    private final FinancialMapper mapper;

    public FinancialStore(FinancialMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void save(String stockCode, Map<String, Object> dartResult) {
        String receiptNo = (String) dartResult.get("receiptNo");
        if (receiptNo == null || receiptNo.isBlank()) {
            throw new IllegalStateException("OpenDART 응답에 공시 접수번호가 없습니다.");
        }
        FinancialReport report = new FinancialReport();
        report.setReceiptNo(receiptNo);
        report.setStockCode(stockCode);
        report.setBusinessYear((Integer) dartResult.get("reportYear"));
        report.setReportCode((String) dartResult.get("reportCode"));
        report.setFsDiv((String) dartResult.get("fsDiv"));
        report.setCollectedAt(LocalDateTime.now(MarketHours.KST));
        mapper.upsertReport(report);

        long reportId = mapper.findReportId(receiptNo, report.getFsDiv());
        for (FinancialValue value : values(dartResult)) {
            mapper.upsertValue(reportId, value);
        }
    }

    /** 수집한 계정을 DB 행으로 바꿉니다. */
    @SuppressWarnings("unchecked")
    static List<FinancialValue> values(Map<String, Object> dartResult) {
        List<FinancialValue> result = new ArrayList<>();
        for (DartService.Account a : (List<DartService.Account>) dartResult.get("accounts")) {
            result.add(new FinancialValue(a.code(), a.name(), a.current(), a.previous(), a.currency(),
                    a.sourceId(), a.sourceName(), a.statement(), a.status()));
        }
        return result;
    }
}