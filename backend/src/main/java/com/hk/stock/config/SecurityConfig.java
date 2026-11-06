package com.hk.stock.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security
 * - 서버 세션 인증. 관심 종목·AI 챗봇만 로그인이 필요하고, 시세·차트·뉴스·실적 조회는 로그인 없이 된다.
 * - 값을 바꾸는 요청(POST·DELETE)은 CSRF 토큰이 필요하다. 프론트는 GET /api/auth/csrf 로 받아 X-CSRF-TOKEN 헤더로 보낸다.
 * - /ws/quotes 는 조회 전용 Push 이고 허용 주소(WebSocketConfig)로 제한한다.
 */
@Configuration
public class SecurityConfig {

	//세션에 저장하는 회원 번호 이름
	public static final String LOGIN_USER_ID = "loginUserId";

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf
						.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
						.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/watchlist/**", "/api/chat/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint((request, response, e) -> writeError(response, HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."))
						.accessDeniedHandler((request, response, e) -> writeError(response, HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다. 새로고침 후 다시 시도해 주세요.")))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable);
		return http.build();
	}

	private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws java.io.IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"error\":\"" + message + "\"}");
	}
}
