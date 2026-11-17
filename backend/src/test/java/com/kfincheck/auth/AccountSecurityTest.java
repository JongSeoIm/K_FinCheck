package com.kfincheck.auth;

import com.kfincheck.config.ApiRateLimitInterceptor;
import com.kfincheck.config.WebMvcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AccountController.class, properties = "api.limit.default-per-minute=2")
@Import({SecurityConfig.class, LoginAttemptGuard.class, WebMvcConfig.class, ApiRateLimitInterceptor.class})
class AccountSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountService accounts;

    @Test
    void protectedApiLimitIsAppliedThroughMvcPerAccount() throws Exception {
        var first = UsernamePasswordAuthenticationToken.authenticated(
                new AccountService.User(7L, "tester", "테스터"), null, List.of());
        var second = UsernamePasswordAuthenticationToken.authenticated(
                new AccountService.User(8L, "another", "다른 계정"), null, List.of());

        for (int request = 0; request < 2; request++) {
            mvc.perform(get("/api/watchlist").with(authentication(first))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/watchlist").with(authentication(first)))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/watchlist").with(authentication(second))).andExpect(status().isOk());
        verify(accounts, times(2)).watchlist(7L);
        verify(accounts).watchlist(8L);
    }

    @Test
    void locksOnlyTheFailingLoginAndIpAndPreservesCsrfAndSessionRotation() throws Exception {
        var wrong = new AccountService.Credentials("tester", "wrong-password");
        var correct = new AccountService.Credentials("tester", "right-password");
        when(accounts.login(wrong)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 실패"));
        when(accounts.login(correct)).thenReturn(new AccountService.User(7L, "tester", "테스터"));

        String wrongBody = "{\"loginId\":\"tester\",\"password\":\"wrong-password\"}";
        String correctBody = "{\"loginId\":\"tester\",\"password\":\"right-password\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongBody))
                .andExpect(status().isForbidden());
        for (int tries = 0; tries < 4; tries++) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(wrongBody))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(wrongBody))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correctBody))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        verify(accounts, times(5)).login(wrong);

        var session = new MockHttpSession();
        String oldId = session.getId();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).with(request -> {
            request.setRemoteAddr("192.0.2.10");
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content(correctBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loginId").value("tester"));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        verify(accounts, times(1)).login(correct);
    }
}
