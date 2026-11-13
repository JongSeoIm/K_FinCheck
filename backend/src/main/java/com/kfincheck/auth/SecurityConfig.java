package com.kfincheck.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository context,
            HttpSessionCsrfTokenRepository csrf) throws Exception {
        return http.cors(cors -> { })
                // AI 서버의 사업보고서 RAG 저장·검색 API 는 브라우저가 아니라 같은 PC 의 AI 서버만 부른다(컨트롤러에서 localhost 확인).
                .csrf(config -> config.csrfTokenRepository(csrf).ignoringRequestMatchers("/api/rag/**"))
                .securityContext(config -> config.securityContextRepository(context))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/watchlist/**", "/api/chat", "/api/chat/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, error) -> {
                            res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"error\":\"로그인이 필요합니다.\"}");
                        })
                        .accessDeniedHandler((req, res, error) -> {
                            res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"error\":\"인증 정보를 갱신한 후 다시 시도해 주세요.\"}");
                        })).build();
    }
}
