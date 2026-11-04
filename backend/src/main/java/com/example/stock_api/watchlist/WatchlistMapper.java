package com.example.stock_api.watchlist;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 모든 쿼리에 회원 번호 조건이 들어갑니다. 회원 번호는 요청이 아니라 세션에서만 가져옵니다. */
@Mapper
public interface WatchlistMapper {

    @Select("SELECT stock_code FROM watchlist WHERE user_id = #{userId} ORDER BY created_at, stock_code")
    List<String> findCodes(@Param("userId") long userId);

    /** 이미 있으면 아무 일도 하지 않습니다. */
    @Insert("INSERT IGNORE INTO watchlist (user_id, stock_code) VALUES (#{userId}, #{code})")
    int add(@Param("userId") long userId, @Param("code") String code);

    @Delete("DELETE FROM watchlist WHERE user_id = #{userId} AND stock_code = #{code}")
    int remove(@Param("userId") long userId, @Param("code") String code);
}
