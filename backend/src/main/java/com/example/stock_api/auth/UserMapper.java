package com.example.stock_api.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT user_id, login_id, password_hash, nickname FROM app_user WHERE login_id = #{loginId}")
    AppUser findByLoginId(@Param("loginId") String loginId);

    @Insert("INSERT INTO app_user (login_id, password_hash, nickname) VALUES (#{loginId}, #{passwordHash}, #{nickname})")
    int insert(@Param("loginId") String loginId, @Param("passwordHash") String passwordHash, @Param("nickname") String nickname);
}
