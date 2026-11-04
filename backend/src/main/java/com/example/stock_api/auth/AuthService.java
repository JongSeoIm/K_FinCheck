package com.example.stock_api.auth;

import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 회원가입·로그인 확인. 비밀번호는 BCrypt 해시로만 저장합니다. */
@Service
public class AuthService {

    private final UserMapper users;
    private final PasswordEncoder encoder;
    // 없는 아이디로 시도해도 해시 비교 시간이 비슷하도록 쓰는 가짜 해시
    private final String dummyHash;

    public static class AuthException extends RuntimeException {
        private final HttpStatus status;

        public AuthException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    public AuthService(UserMapper users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public void signup(String loginId, String password, String passwordConfirm, String nickname) {
        String error = AuthValidator.signupError(loginId, password, passwordConfirm, nickname);
        if (error != null) {
            throw new AuthException(HttpStatus.BAD_REQUEST, error);
        }
        String id = loginId.trim();
        if (users.findByLoginId(id) != null) {
            throw new AuthException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        UserRow row = new UserRow();
        row.setLoginId(id);
        row.setPasswordHash(encoder.encode(password));
        row.setNickname(nickname.trim());
        try {
            users.insert(row);
        } catch (DuplicateKeyException e) {
            // 동시에 같은 아이디로 가입한 경우
            throw new AuthException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
    }

    /** 아이디·비밀번호가 맞으면 사용자 정보를, 아니면 401 예외를 던집니다. 어느 쪽이 틀렸는지는 알려 주지 않습니다. */
    public AuthUser authenticate(String loginId, String password) {
        String id = loginId == null ? "" : loginId.trim();
        String raw = password == null ? "" : password;
        Map<String, Object> row = users.findByLoginId(id);

        String hash = row == null ? dummyHash : String.valueOf(row.get("password_hash"));
        boolean matches = !AuthValidator.tooLongForBcrypt(raw) && encoder.matches(raw, hash);
        if (row == null || !matches) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return new AuthUser(((Number) row.get("user_id")).longValue(),
                String.valueOf(row.get("login_id")), String.valueOf(row.get("nickname")));
    }
}
