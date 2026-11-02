package com.kfincheck.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class AccountController {
    private final AccountService service;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    public AccountController(AccountService service, HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf) {
        this.service = service; this.contexts = contexts; this.csrf = csrf;
    }
    @GetMapping("/api/auth/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/api/auth/me") public AccountService.User me(Authentication auth) { return user(auth); }
    @PostMapping("/api/auth/signup") @ResponseStatus(HttpStatus.CREATED)
    public AccountService.User signup(@RequestBody AccountService.Registration body) { return service.signup(body); }
    @PostMapping("/api/auth/login")
    public AccountService.User login(@RequestBody AccountService.Credentials body, HttpServletRequest request, HttpServletResponse response) {
        var user = service.login(body);
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth, request, response);
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
        SecurityContextHolder.setContext(context); contexts.saveContext(context, request, response);
        csrf.saveToken(null, request, response);
        return user;
    }
    @PostMapping("/api/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false); if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
    @GetMapping("/api/watchlist") public List<String> watchlist(Authentication auth) { return service.watchlist(user(auth).userId()); }
    @PostMapping("/api/watchlist/{code}") public List<String> add(Authentication auth, @PathVariable("code") String code) { return service.add(user(auth).userId(), code); }
    @DeleteMapping("/api/watchlist/{code}") public List<String> remove(Authentication auth, @PathVariable("code") String code) { return service.remove(user(auth).userId(), code); }
    private static AccountService.User user(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AccountService.User user))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return user;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", Objects.requireNonNullElse(error.getReason(), "요청을 처리하지 못했습니다."))); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseError() { return ResponseEntity.status(503).body(Map.of("error", "계정 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.")); }
}
