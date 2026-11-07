package com.hk.stock.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

//회원가입 요청: POST /api/auth/signup
@Data
public class SignupRequest {
	@NotBlank(message = "아이디를 입력하세요.")
	@Pattern(regexp = "[a-z0-9]{4,20}", message = "아이디는 영문 소문자·숫자 4~20자입니다.")
	private String loginId;

	@NotBlank(message = "비밀번호를 입력하세요.")
	@Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다.")
	private String password;

	@NotBlank(message = "닉네임을 입력하세요.")
	@Size(min = 2, max = 20, message = "닉네임은 2~20자입니다.")
	private String nickname;
}
