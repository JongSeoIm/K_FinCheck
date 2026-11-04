package com.example.stock_api.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.stock_api.common.ApiException;

/** 회원가입·로그인 확인. 비밀번호는 BCrypt 해시로만 저장합니다. */
@Service
public class AuthService {

    /** BCrypt 가 처리할 수 있는 비밀번호 최대 길이 (바이트) */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserMapper users;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper users) {
        this.users = users;
    }

    public void signup(AuthRequests.Signup request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "비밀번호가 너무 깁니다. 한글은 24자까지 사용할 수 있습니다.");
        }
        if (users.findByLoginId(request.loginId()) != null) {
            throw loginIdTaken();
        }
        try {
            users.insert(request.loginId(), encoder.encode(request.password()), request.nickname().trim());
        } catch (DuplicateKeyException e) {
            // 위 확인과 저장 사이에 같은 아이디가 먼저 가입한 경우
            throw loginIdTaken();
        }
    }

    public LoginUser login(AuthRequests.Login request) {
        AppUser user = users.findByLoginId(request.loginId());
        if (user == null || !encoder.matches(request.password(), user.getPasswordHash())) {
            // 아이디가 있는지 알 수 없도록 같은 문구를 씁니다
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return new LoginUser(user.getUserId(), user.getLoginId(), user.getNickname());
    }

    private static ApiException loginIdTaken() {
        return new ApiException(HttpStatus.CONFLICT, "LOGIN_ID_TAKEN", "이미 사용 중인 아이디입니다.");
    }
}
