package com.example.stock_api.earnings;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 실적 저장용 MyBatis 매퍼 (company / financial_report / financial_value). */
@Mapper
public interface EarningsMapper {

    @Insert("INSERT INTO company (stock_code, company_name, market_type, dart_corp_code) "
            + "VALUES (#{stockCode}, #{name}, #{market}, #{corpCode}) "
            + "ON DUPLICATE KEY UPDATE company_name = VALUES(company_name), "
            + "market_type = VALUES(market_type), dart_corp_code = VALUES(dart_corp_code)")
    void upsertCompany(@Param("stockCode") String stockCode, @Param("name") String name,
                       @Param("market") String market, @Param("corpCode") String corpCode);

    @Insert("INSERT INTO financial_report (stock_code, receipt_no, business_year, report_code, fs_div) "
            + "VALUES (#{stockCode}, #{receiptNo}, #{year}, #{reportCode}, #{fsDiv}) "
            + "ON DUPLICATE KEY UPDATE collected_at = CURRENT_TIMESTAMP")
    void upsertReport(@Param("stockCode") String stockCode, @Param("receiptNo") String receiptNo,
                      @Param("year") int year, @Param("reportCode") String reportCode,
                      @Param("fsDiv") String fsDiv);

    @Select("SELECT report_id FROM financial_report WHERE receipt_no = #{receiptNo} AND fs_div = #{fsDiv}")
    Long findReportId(@Param("receiptNo") String receiptNo, @Param("fsDiv") String fsDiv);

    @Insert("INSERT INTO financial_value (report_id, account_code, account_name, source_account_name, "
            + "current_amount, previous_amount, currency, value_status) "
            + "VALUES (#{reportId}, #{code}, #{name}, #{sourceName}, "
            + "#{current,jdbcType=DECIMAL}, #{previous,jdbcType=DECIMAL}, #{currency}, #{status}) "
            + "ON DUPLICATE KEY UPDATE account_name = VALUES(account_name), "
            + "source_account_name = VALUES(source_account_name), current_amount = VALUES(current_amount), "
            + "previous_amount = VALUES(previous_amount), currency = VALUES(currency), "
            + "value_status = VALUES(value_status)")
    void upsertValue(@Param("reportId") long reportId, @Param("code") String code, @Param("name") String name,
                     @Param("sourceName") String sourceName, @Param("current") Long current,
                     @Param("previous") Long previous, @Param("currency") String currency,
                     @Param("status") String status);

    /** 가장 최근 사업보고서 중 1시간 이내에 수집된 것. 없으면 null. */
    @Select("SELECT report_id, receipt_no, business_year, fs_div FROM financial_report "
            + "WHERE stock_code = #{stockCode} AND report_code = '11011' "
            + "AND collected_at > NOW() - INTERVAL 1 HOUR "
            + "ORDER BY business_year DESC, collected_at DESC LIMIT 1")
    Map<String, Object> findFreshReport(@Param("stockCode") String stockCode);

    @Select("SELECT account_code, current_amount, previous_amount FROM financial_value WHERE report_id = #{reportId}")
    List<Map<String, Object>> findValues(@Param("reportId") long reportId);
}
