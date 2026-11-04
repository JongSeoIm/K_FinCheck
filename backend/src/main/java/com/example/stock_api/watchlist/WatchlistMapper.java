package com.example.stock_api.watchlist;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** watchlist 접근용 MyBatis 매퍼. 모든 조회·변경은 로그인한 회원 번호로만 합니다. */
@Mapper
public interface WatchlistMapper {

    @Select("SELECT stock_code FROM watchlist WHERE user_id = #{userId} ORDER BY created_at, stock_code")
    List<String> findCodes(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM company WHERE stock_code = #{stockCode}")
    int companyExists(@Param("stockCode") String stockCode);

    // 같은 계정의 같은 종목은 (user_id, stock_code) 기본키로 중복 등록되지 않습니다.
    @Insert("INSERT IGNORE INTO watchlist (user_id, stock_code) VALUES (#{userId}, #{stockCode})")
    int insert(@Param("userId") long userId, @Param("stockCode") String stockCode);

    @Delete("DELETE FROM watchlist WHERE user_id = #{userId} AND stock_code = #{stockCode}")
    int delete(@Param("userId") long userId, @Param("stockCode") String stockCode);
}
