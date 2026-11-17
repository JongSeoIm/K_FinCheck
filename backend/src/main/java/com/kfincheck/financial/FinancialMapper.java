package com.kfincheck.financial;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FinancialMapper {
    record Header(long reportId, String stockCode, String corpCode, int businessYear, String reportCode,
                  String fsDiv, String receiptNo, LocalDateTime collectedAt) { }
    record Value(String accountCode, String accountName, BigDecimal currentAmount, BigDecimal previousAmount,
                 String currency, String valueStatus, String sourceAccountId, String sourceAccountName) { }

    Header latest(@Param("stock") String stock, @Param("year") int year);
    Header byReceipt(@Param("receipt") String receipt, @Param("fsDiv") String fsDiv);
    List<Value> values(long reportId);
    int upsertCompany(@Param("stock") String stock, @Param("name") String name, @Param("corp") String corp);
    int upsertReport(@Param("report") FinancialReport report, @Param("collectedAt") LocalDateTime collectedAt);
    int upsertValue(@Param("reportId") long reportId, @Param("code") String code,
                    @Param("metric") FinancialReport.Metric metric);
}
