package com.example.stock_api.earnings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 실적의 DB 저장·조회. 저장은 한 트랜잭션으로 처리합니다. */
@Service
public class EarningsStore {

    // 프로젝트 공통 계정 코드 ↔ 화면용 key / 이름
    private static final String[][] ACCOUNTS = {
            {"REVENUE", "revenue", "매출액"},
            {"OPERATING_PROFIT", "operatingProfit", "영업이익"},
            {"NET_INCOME", "netIncome", "당기순이익"}};

    /** DART 에서 받은 한 계정의 당기·전기 금액. 값이 없으면 null (0으로 바꾸지 않음). */
    public record ValueRow(String accountCode, String accountName, String sourceName,
                           Long current, Long previous, String currency) {}

    private final EarningsMapper mapper;

    public EarningsStore(EarningsMapper mapper) {
        this.mapper = mapper;
    }

    public static String accountCode(String key) {
        for (String[] a : ACCOUNTS) {
            if (a[1].equals(key)) {
                return a[0];
            }
        }
        throw new IllegalArgumentException("알 수 없는 계정: " + key);
    }

    @Transactional
    public void save(String stockCode, String companyName, String market, String corpCode,
                     EarningsService.Annual annual, List<ValueRow> rows) {
        mapper.upsertCompany(stockCode, companyName, market, corpCode);
        String fsDiv = "연결".equals(annual.basis()) ? "CFS" : "OFS";
        mapper.upsertReport(stockCode, annual.receiptNo(), annual.businessYear(), "11011", fsDiv);
        Long reportId = mapper.findReportId(annual.receiptNo(), fsDiv);
        if (reportId == null) {
            throw new IllegalStateException("보고서 저장 확인 실패");
        }
        for (ValueRow row : rows) {
            String status = row.current() == null || row.previous() == null ? "MISSING" : "AVAILABLE";
            mapper.upsertValue(reportId, row.accountCode(), row.accountName(), row.sourceName(),
                    row.current(), row.previous(), row.currency(), status);
        }
    }

    /** 1시간 이내에 수집한 최신 사업보고서가 있으면 화면용 객체로 복원하고, 없으면 null. */
    @Transactional(readOnly = true)
    public EarningsService.Annual findFresh(String stockCode) {
        Map<String, Object> report = mapper.findFreshReport(stockCode);
        if (report == null) {
            return null;
        }
        long reportId = ((Number) report.get("report_id")).longValue();
        int year = ((Number) report.get("business_year")).intValue();
        String receiptNo = String.valueOf(report.get("receipt_no"));
        String basis = "CFS".equals(String.valueOf(report.get("fs_div"))) ? "연결" : "별도";

        List<Map<String, Object>> values = mapper.findValues(reportId);
        List<EarningsService.Line> lines = new ArrayList<>();
        for (String[] account : ACCOUNTS) {
            Long current = null;
            Long previous = null;
            for (Map<String, Object> v : values) {
                if (account[0].equals(String.valueOf(v.get("account_code")))) {
                    current = toLong(v.get("current_amount"));
                    previous = toLong(v.get("previous_amount"));
                }
            }
            EarningsMath.Change change = EarningsMath.change(current, previous);
            lines.add(new EarningsService.Line(account[1], account[2], current, previous,
                    change.percent(), change.status()));
        }
        return new EarningsService.Annual(year, year - 1, basis, "원", receiptNo,
                EarningsMath.dartUrl(receiptNo), lines);
    }

    private static Long toLong(Object value) {
        return value instanceof BigDecimal d ? Long.valueOf(d.longValue())
                : value instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }
}
