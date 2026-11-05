package com.example.stock_api.auth;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/** 회원가입·로그인·로그아웃. 로그인 상태는 Spring Security 서버 세션으로 유지합니다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final SecurityContextRepository contexts;

    public AuthController(AuthService auth, SecurityContextRepository contexts) {
        this.auth = auth;
        this.contexts = contexts;
    }

    /** 변경 요청(POST·DELETE)에 함께 보낼 CSRF 토큰 */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /** 가입만 하고 자동 로그인은 하지 않습니다. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public void signup(@Valid @RequestBody AuthRequests.Signup body) {
        auth.signup(body);
    }

    @PostMapping("/login")
    public LoginUser login(@Valid @RequestBody AuthRequests.Login body,
                           HttpServletRequest request, HttpServletResponse response) {
        LoginUser user = auth.login(body);
        // 로그인 전 세션 ID 를 이어 쓰지 않도록 바꿉니다
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return user;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** 로그인 전이면 {"user": null} */
    @GetMapping("/me")
    public Map<String, LoginUser> me(@AuthenticationPrincipal LoginUser user) {
        return Collections.singletonMap("user", user);
    }
}