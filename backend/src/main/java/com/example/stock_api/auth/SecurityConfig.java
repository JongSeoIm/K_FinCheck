package com.example.stock_api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 인증·접근 제어 설정.
 * - 로그인은 AuthController 가 직접 처리하고 서버 세션(JSESSIONID)에 저장합니다.
 * - 관심종목·챗봇 API 만 로그인이 필요하고, 기존 시세·뉴스·실적 API 는 그대로 공개입니다.
 * - 변경 요청(POST·DELETE)은 CSRF 토큰이 필요합니다. 토큰은 XSRF-TOKEN 쿠키로 내려가고,
 *   프론트가 같은 값을 X-XSRF-TOKEN 헤더에 실어 보냅니다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 사용하지 않는 기본 계정(콘솔에 임시 비밀번호가 찍히는 것)을 만들지 않기 위한 빈
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("사용하지 않는 방식입니다.");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // 단일 페이지 앱용: 모든 요청에서 토큰 쿠키를 내려 줍니다.
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/watchlist", "/api/watchlist/**", "/api/chat", "/api/chat/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"로그인이 필요합니다.\"}");
                }));
        return http.build();
    }
}
