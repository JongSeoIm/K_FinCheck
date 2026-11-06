package com.hk.stock.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hk.stock.controller.AuthController;
import com.hk.stock.controller.WatchlistController;
import com.hk.stock.dtos.UserDto;
import com.hk.stock.service.UserService;
import com.hk.stock.service.WatchlistService;

//로그인·CSRF 규칙 (DB 없이 웹 계층만 실행)
@WebMvcTest(controllers = { AuthController.class, WatchlistController.class })
@Import(SecurityConfig.class)
class SecurityConfigTests {

	private static final String LOGIN = "{\"loginId\":\"tester\",\"password\":\"password123\"}";

	@Autowired
	private MockMvc mvc;
	@MockitoBean
	private UserService userService;
	@MockitoBean
	private WatchlistService watchlistService;

	@Test
	void 로그인_없이_관심_종목을_조회하면_401() throws Exception {
		mvc.perform(get("/api/watchlist"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("로그인이 필요합니다."));
	}

	@Test
	void CSRF_토큰_없는_변경_요청은_403() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void CSRF_토큰을_받아_보내면_로그인되고_세션으로_인증된다() throws Exception {
		UserDto user = new UserDto();
		user.setUserId(7L);
		user.setLoginId("tester");
		when(userService.login(any())).thenReturn(user);
		when(watchlistService.getWatchlist(7L)).thenReturn(java.util.List.of());

		MockHttpSession session = new MockHttpSession();
		String body = mvc.perform(get("/api/auth/csrf").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
				.andReturn().getResponse().getContentAsString();
		String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("token").asText();

		mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
				.contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isOk());

		mvc.perform(get("/api/watchlist").session(session)).andExpect(status().isOk());
	}

	@Test
	void 회원가입은_로그인_상태를_만들지_않는다() throws Exception {
		UserDto user = new UserDto();
		user.setUserId(8L);
		when(userService.signup(any())).thenReturn(user);
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/api/auth/signup").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"tester\",\"password\":\"password123\",\"nickname\":\"테스터\"}"))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/watchlist").session(session)).andExpect(status().isUnauthorized());
	}
}
