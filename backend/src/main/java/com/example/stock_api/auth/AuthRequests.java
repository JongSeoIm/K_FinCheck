package com.example.stock_api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원가입·로그인 요청 본문 */
public final class AuthRequests {

    private AuthRequests() {
    }

    public record Signup(
            @NotBlank(message = "아이디를 입력해 주세요.")
            @Pattern(regexp = "[a-z0-9_]{4,20}", message = "아이디는 영문 소문자·숫자·밑줄 4~20자입니다.")
            String loginId,

            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8~72자입니다.")
            String password,

            @NotBlank(message = "닉네임을 입력해 주세요.")
            @Size(max = 20, message = "닉네임은 20자 이하입니다.")
            String nickname) {
    }

    public record Login(
            @NotBlank(message = "아이디를 입력해 주세요.") String loginId,
            @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {
    }
}
