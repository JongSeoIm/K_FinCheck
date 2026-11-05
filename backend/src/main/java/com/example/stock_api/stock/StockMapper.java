package com.example.stock_api.stock;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 지원 종목 조회 (company 테이블).
 * 컬럼 이름은 팀 ERD 를 따르고, 별칭(AS)으로 Stock 객체의 필드 이름(code, name, market …)에 맞춥니다.
 */
@Mapper
public interface StockMapper {

    String COLUMNS = "stock_code AS code, company_name AS name, market_type AS market, industry, dart_corp_code";

    @Select("SELECT " + COLUMNS + " FROM company ORDER BY stock_code")
    List<Stock> findAll();

    @Select("SELECT " + COLUMNS + " FROM company WHERE stock_code = #{code}")
    Stock findByCode(@Param("code") String code);

    /** 기업명 부분 일치 또는 종목코드 앞부분 일치 */
    @Select("SELECT " + COLUMNS + " FROM company"
            + " WHERE company_name LIKE CONCAT('%', #{q}, '%') OR stock_code LIKE CONCAT(#{q}, '%')"
            + " ORDER BY CASE WHEN stock_code = #{q} OR company_name = #{q} THEN 0 ELSE 1 END, stock_code"
            + " LIMIT 20")
    List<Stock> search(@Param("q") String q);

    @Update("UPDATE company SET dart_corp_code = #{corpCode} WHERE stock_code = #{code} AND dart_corp_code IS NULL")
    int fillDartCorpCode(@Param("code") String code, @Param("corpCode") String corpCode);

    @Select("SELECT 1")
    int ping();
}