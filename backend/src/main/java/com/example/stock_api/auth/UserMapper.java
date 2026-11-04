package com.example.stock_api.auth;

import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** app_user 접근용 MyBatis 매퍼. */
@Mapper
public interface UserMapper {

    @Insert("INSERT INTO app_user (login_id, password_hash, nickname) "
            + "VALUES (#{loginId}, #{passwordHash}, #{nickname})")
    @Options(useGeneratedKeys = true, keyProperty = "userId", keyColumn = "user_id")
    int insert(UserRow row);

    @Select("SELECT user_id, login_id, password_hash, nickname FROM app_user WHERE login_id = #{loginId}")
    Map<String, Object> findByLoginId(@Param("loginId") String loginId);
}
