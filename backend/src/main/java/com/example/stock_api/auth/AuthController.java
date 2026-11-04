package com.example.stock_api.auth;

import java.util.Collections;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/** 회원가입·로그인·로그아웃. 로그인 상태는 서버 세션(쿠키 JSESSIONID)으로 유지합니다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** 가입만 하고 자동 로그인은 하지 않습니다. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public void signup(@Valid @RequestBody AuthRequests.Signup body) {
        auth.signup(body);
    }

    @PostMapping("/login")
    public LoginUser login(@Valid @RequestBody AuthRequests.Login body, HttpServletRequest request) {
        LoginUser user = auth.login(body);
        // 로그인 전 세션을 이어 쓰지 않도록 새 세션을 만듭니다
        HttpSession before = request.getSession(false);
        if (before != null) {
            before.invalidate();
        }
        request.getSession(true).setAttribute(LoginUser.SESSION_KEY, user);
        return user;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** 로그인 전이면 {"user": null} */
    @GetMapping("/me")
    public Map<String, LoginUser> me(@SessionAttribute(name = LoginUser.SESSION_KEY, required = false) LoginUser user) {
        return Collections.singletonMap("user", user);
    }
}
