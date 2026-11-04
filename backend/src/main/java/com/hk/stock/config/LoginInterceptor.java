package com.hk.stock.config;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

//로그인한 사용자만 쓸 수 있는 API(관심 종목·AI 챗봇) 앞에서 세션을 확인한다
@Component
public class LoginInterceptor implements HandlerInterceptor {

	//세션에 저장하는 회원 번호 이름
	public static final String LOGIN_USER_ID = "loginUserId";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		//CORS 사전 요청(OPTIONS)은 쿠키 없이 오므로 통과시킨다
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(LOGIN_USER_ID) != null) return true;

		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"error\":\"로그인이 필요합니다.\"}");
		return false;
	}
}
