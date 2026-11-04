package com.example.stock_api.auth;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** 회원가입 입력 검사 (스프링과 무관한 순수 검사). 문제가 있으면 안내 문구를, 없으면 null 을 돌려줍니다. */
final class AuthValidator {

    private static final Pattern LOGIN_ID = Pattern.compile("^[A-Za-z0-9_]{4,20}$");
    private static final int BCRYPT_MAX_BYTES = 72;

    private AuthValidator() {}

    static String signupError(String loginId, String password, String passwordConfirm, String nickname) {
        if (loginId == null || !LOGIN_ID.matcher(loginId.trim()).matches()) {
            return "아이디는 영문·숫자·밑줄 4~20자로 입력해 주세요.";
        }
        if (password == null || password.length() < 8 || password.length() > 50) {
            return "비밀번호는 8~50자로 입력해 주세요.";
        }
        if (tooLongForBcrypt(password)) {
            return "비밀번호가 너무 깁니다. 더 짧게 입력해 주세요.";
        }
        if (!password.equals(passwordConfirm)) {
            return "비밀번호 확인이 일치하지 않습니다.";
        }
        String name = nickname == null ? "" : nickname.trim();
        if (name.length() < 2 || name.length() > 20) {
            return "닉네임은 2~20자로 입력해 주세요.";
        }
        return null;
    }

    /** BCrypt 는 비밀번호의 앞 72바이트만 사용하므로, 그보다 긴 입력은 받지 않습니다. */
    static boolean tooLongForBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
