package com.example.stock_api.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.chat.ChatMemory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** 회원가입 / 로그인 / 로그아웃 / 로그인 상태 확인. 프론트의 /api/auth/* 요청이 이리로 전달됩니다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(String loginId, String password, String passwordConfirm, String nickname) {}

    public record LoginRequest(String loginId, String password) {}

    private final AuthService service;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    private final ChatMemory chatMemory;

    public AuthController(AuthService service, ChatMemory chatMemory) {
        this.service = service;
        this.chatMemory = chatMemory;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest body) {
        try {
            service.signup(body.loginId(), body.password(), body.passwordConfirm(), body.nickname());
            return ResponseEntity.status(201).body(Map.of("message", "가입이 완료되었습니다."));
        } catch (AuthService.AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "가입 처리 중 문제가 발생했습니다."));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body,
                                   HttpServletRequest request, HttpServletResponse response) {
        AuthUser user;
        try {
            user = service.authenticate(body.loginId(), body.password());
        } catch (AuthService.AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "로그인 처리 중 문제가 발생했습니다."));
        }

        // 세션 고정 공격 방지: 로그인 시 세션 ID 를 새로 바꿉니다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(userBody(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        // 로그아웃하면 이 사용자의 챗봇 대화 문맥도 지웁니다.
        if (user != null) {
            chatMemory.clear(user.userId());
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "로그아웃되었습니다."));
    }

    /** 새로고침할 때 로그인 상태를 확인합니다. 로그인 전에도 200 으로 {"authenticated": false} 를 줍니다. */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthUser user) {
        return user == null ? Map.of("authenticated", false) : userBody(user);
    }

    private static Map<String, Object> userBody(AuthUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("userId", user.userId());
        body.put("loginId", user.loginId());
        body.put("nickname", user.nickname());
        return body;
    }
}
