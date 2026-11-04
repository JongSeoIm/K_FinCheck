package com.example.stock_api.financial;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 공시·재무 수치. 같은 공시를 다시 저장하면 고유 키 덕분에 새 행 없이 내용만 갱신됩니다. */
@Mapper
public interface FinancialMapper {

    String REPORT = "SELECT report_id, receipt_no, stock_code, business_year, report_code, fs_div, collected_at"
            + " FROM financial_report";

    @Insert("INSERT INTO financial_report (receipt_no, stock_code, business_year, report_code, fs_div, collected_at)"
            + " VALUES (#{receiptNo}, #{stockCode}, #{businessYear}, #{reportCode}, #{fsDiv}, #{collectedAt})"
            + " ON DUPLICATE KEY UPDATE collected_at = VALUES(collected_at)")
    int upsertReport(FinancialReport report);

    @Select("SELECT report_id FROM financial_report WHERE receipt_no = #{receiptNo} AND fs_div = #{fsDiv}")
    Long findReportId(@Param("receiptNo") String receiptNo, @Param("fsDiv") String fsDiv);

    @Insert("INSERT INTO financial_value (report_id, account_code, account_name, current_amount, previous_amount,"
            + " currency, source_account_id, source_account_name, source_statement, value_status)"
            + " VALUES (#{reportId}, #{v.accountCode}, #{v.accountName}, #{v.currentAmount}, #{v.previousAmount},"
            + " #{v.currency}, #{v.sourceAccountId}, #{v.sourceAccountName}, #{v.sourceStatement}, #{v.valueStatus})"
            + " ON DUPLICATE KEY UPDATE account_name = VALUES(account_name), current_amount = VALUES(current_amount),"
            + " previous_amount = VALUES(previous_amount), currency = VALUES(currency),"
            + " source_account_id = VALUES(source_account_id), source_account_name = VALUES(source_account_name),"
            + " source_statement = VALUES(source_statement),"
            + " value_status = VALUES(value_status)")
    int upsertValue(@Param("reportId") long reportId, @Param("v") FinancialValue value);

    /** 같은 사업연도의 정정 공시가 있으면 접수번호가 큰(나중) 공시를 씁니다 */
    @Select(REPORT + " WHERE stock_code = #{stockCode} ORDER BY business_year DESC, receipt_no DESC LIMIT 1")
    FinancialReport findLatest(@Param("stockCode") String stockCode);

    @Select(REPORT + " WHERE stock_code = #{stockCode} AND business_year = #{year} ORDER BY receipt_no DESC LIMIT 1")
    FinancialReport findByYear(@Param("stockCode") String stockCode, @Param("year") int year);

    @Select("SELECT account_code, account_name, current_amount, previous_amount, currency,"
            + " source_account_id, source_account_name, source_statement, value_status"
            + " FROM financial_value WHERE report_id = #{reportId} ORDER BY value_id")
    List<FinancialValue> findValues(@Param("reportId") long reportId);
}