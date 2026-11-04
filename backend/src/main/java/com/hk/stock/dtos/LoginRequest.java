package com.hk.stock.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

//로그인 요청: POST /api/auth/login
@Data
public class LoginRequest {
	@NotBlank(message = "아이디를 입력하세요.")
	private String loginId;

	@NotBlank(message = "비밀번호를 입력하세요.")
	private String password;
}
