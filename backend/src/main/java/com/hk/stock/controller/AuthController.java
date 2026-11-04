package com.hk.stock.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hk.stock.config.LoginInterceptor;
import com.hk.stock.dtos.LoginRequest;
import com.hk.stock.dtos.SignupRequest;
import com.hk.stock.dtos.UserDto;
import com.hk.stock.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

//회원가입·로그인·로그아웃 (세션 방식, 쿠키 JSESSIONID)
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	@Autowired
	private UserService userService;

	//회원가입 후 바로 로그인 상태로 만든다: POST /api/auth/signup → 201 {"user":{...}}
	@PostMapping("/signup")
	public ResponseEntity<Map<String, UserDto>> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
		UserDto user = userService.signup(body);
		startSession(request, user);
		return ResponseEntity.status(HttpStatus.CREATED).body(userMap(user));
	}

	//로그인: POST /api/auth/login → {"user":{...}}
	@PostMapping("/login")
	public Map<String, UserDto> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
		UserDto user = userService.login(body);
		startSession(request, user);
		return userMap(user);
	}

	//로그아웃: 세션 삭제 → 204
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) session.invalidate();
		return ResponseEntity.noContent().build();
	}

	//현재 로그인 사용자: GET /api/auth/me → {"user":{...}} / 로그인 안 했으면 401
	@GetMapping("/me")
	public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		Long userId = session == null ? null : (Long) session.getAttribute(LoginInterceptor.LOGIN_USER_ID);
		UserDto user = userId == null ? null : userService.getUser(userId);
		if (user == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "로그인이 필요합니다."));
		}
		return ResponseEntity.ok(new HashMap<>(userMap(user)));
	}

	//로그인 전 세션 ID를 그대로 쓰지 않도록 새 ID로 바꾼다 (세션 고정 공격 방지)
	private void startSession(HttpServletRequest request, UserDto user) {
		request.getSession(true);
		request.changeSessionId();
		request.getSession().setAttribute(LoginInterceptor.LOGIN_USER_ID, user.getUserId());
	}

	private Map<String, UserDto> userMap(UserDto user) {
		Map<String, UserDto> map = new HashMap<>();
		map.put("user", user);
		return map;
	}
}
