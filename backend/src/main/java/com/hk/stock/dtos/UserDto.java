package com.hk.stock.dtos;

import java.time.LocalDateTime;

import org.apache.ibatis.type.Alias;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//회원 계정 (app_user 테이블)
@Alias(value = "userDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
	private Long userId;			//회원 번호
	private String loginId;			//로그인 ID
	@JsonIgnore
	private String passwordHash;	//BCrypt 해시 (응답에 절대 포함하지 않는다)
	private String nickname;		//닉네임
	private LocalDateTime regdate;	//가입일
}
