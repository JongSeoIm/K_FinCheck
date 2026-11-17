package com.kfincheck.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.kfincheck.auth.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiRateLimitInterceptorTest {
    private long now = 1_000_000;

    /** 1분 한도: 사업보고서 2, 나머지 3. 하루 한도 0(끔), 정지 0(끔). */
    private final ApiRateLimitInterceptor interceptor = interceptor(0, 0);

    private ApiRateLimitInterceptor interceptor(int dailyLimit, int suspendAfter) {
        return new ApiRateLimitInterceptor(new ApiRateLimiter(() -> now),
                new ApiAbuseGuard(() -> now, dailyLimit, suspendAfter, 10 * 60_000L, 10 * 60_000L), 3, 2, 3, 3, 3);
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest browser(String path, String ip) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(ip);
        request.addHeader("Origin", "http://localhost:3000");
        return request;
    }

    private static boolean call(ApiRateLimitInterceptor target, MockHttpServletRequest request) throws Exception {
        return target.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private boolean call(MockHttpServletRequest request) throws Exception {
        return call(interceptor, request);
    }

    private static void login(long userId) {
        var user = new AccountService.User(userId, "tester" + userId, "테스터");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void blocksAfterLimitWith429AndAllowsAfterOneMinute() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(call(browser("/api/stocks/005930/price", "10.0.0.1"))).isTrue();

        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(browser("/api/stocks/005930/price", "10.0.0.1"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("요청이 너무 많습니다");

        now += 60_000;
        assertThat(call(browser("/api/stocks/005930/price", "10.0.0.1"))).isTrue();
    }

    @Test
    void countsEachIpAndGroupSeparately() throws Exception {
        for (int i = 0; i < 3; i++) call(browser("/api/stocks/005930/price", "10.0.0.1"));
        assertThat(call(browser("/api/stocks/005930/price", "10.0.0.2"))).isTrue();       // 다른 IP
        assertThat(call(browser("/api/stocks/005930/financials", "10.0.0.1"))).isTrue();  // 다른 묶음
    }

    @Test
    void countsLoggedInUserByAccountNotIp() throws Exception {
        login(7L);
        call(browser("/api/stocks/005930/price", "10.0.0.1"));
        call(browser("/api/stocks/005930/price", "10.0.0.2"));
        call(browser("/api/stocks/005930/price", "10.0.0.3"));
        assertThat(call(browser("/api/stocks/005930/price", "10.0.0.4"))).isFalse();  // IP 를 바꿔도 같은 계정
    }

    @Test
    void businessReportHasLowestLimit() throws Exception {
        assertThat(call(browser("/api/stocks/005930/business-report", "10.0.0.1"))).isTrue();
        assertThat(call(browser("/api/stocks/005930/business-report/document", "10.0.0.1"))).isTrue();
        assertThat(call(browser("/api/stocks/005930/business-report", "10.0.0.1"))).isFalse();
    }

    @Test
    void countsLoopbackRequestsWithoutOrigin() throws Exception {
        for (int i = 0; i < 3; i++) {
            var request = new MockHttpServletRequest("GET", "/api/stocks/005930/daily-price");
            request.setRemoteAddr("127.0.0.1");
            assertThat(call(request)).isTrue();
        }
        var request = new MockHttpServletRequest("GET", "/api/stocks/005930/daily-price");
        request.setRemoteAddr("127.0.0.1");
        assertThat(call(request)).isFalse();
    }

    @Test
    void capsLoginAndSignupByIpWithoutLimitingLogout() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(call(browser("/api/auth/login", "10.0.0.1"))).isTrue();
        login(7L);
        assertThat(call(browser("/api/auth/signup", "10.0.0.1"))).isFalse();
        assertThat(call(browser("/api/auth/logout", "10.0.0.1"))).isTrue();
        assertThat(call(browser("/api/auth/signup", "10.0.0.2"))).isTrue();
    }

    @Test
    void dailyLimitStopsAccountThatStaysUnderMinuteLimit() throws Exception {
        var target = interceptor(5, 0);
        login(7L);
        for (int i = 0; i < 5; i++) {
            assertThat(call(target, browser("/api/stocks/005930/financials", "10.0.0.1"))).isTrue();
            now += 30_000;  // 1분 한도(3)는 넘지 않게 천천히
        }
        var response = new MockHttpServletResponse();
        assertThat(target.preHandle(browser("/api/stocks/005930/news", "10.0.0.1"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("오늘 이용 한도");

        login(8L);  // 다른 계정은 영향 없음
        assertThat(call(target, browser("/api/stocks/005930/news", "10.0.0.1"))).isTrue();
    }

    @Test
    void repeatedViolationsSuspendAccountIncludingChat() throws Exception {
        var target = interceptor(0, 4);
        login(7L);
        for (int i = 0; i < 3; i++) call(target, browser("/api/stocks/005930/price", "10.0.0.1"));  // 1분 한도까지
        for (int i = 0; i < 3; i++) call(target, browser("/api/stocks/005930/price", "10.0.0.1"));  // 위반 3번: 아직 정지 아님

        var response = new MockHttpServletResponse();
        assertThat(target.preHandle(browser("/api/stocks/005930/price", "10.0.0.1"), response, new Object())).isFalse();  // 위반 4번 → 정지
        assertThat(response.getContentAsString()).contains("일시 정지").contains("10분");
        assertThat(response.getHeader("Retry-After")).isEqualTo("600");

        now += 2 * 60_000;  // 1분 한도는 풀렸지만 정지는 계속
        assertThat(call(target, browser("/api/stocks/005930/financials", "10.0.0.1"))).isFalse();
        var chat = new MockHttpServletRequest("POST", "/api/chat");
        chat.setRemoteAddr("10.0.0.1");
        chat.addHeader("Origin", "http://localhost:3000");
        assertThat(call(target, chat)).isFalse();  // 챗봇도 정지

        login(8L);
        assertThat(call(target, browser("/api/stocks/005930/price", "10.0.0.1"))).isTrue();  // 다른 계정은 영향 없음

        login(7L);
        now += 8 * 60_000;  // 정지 10분 끝
        assertThat(call(target, browser("/api/stocks/005930/price", "10.0.0.1"))).isTrue();
    }

    @Test
    void chatIsNotCountedHereWhenNotSuspended() throws Exception {
        for (int i = 0; i < 20; i++) {
            var chat = new MockHttpServletRequest("POST", "/api/chat");
            chat.setRemoteAddr("10.0.0.1");
            chat.addHeader("Origin", "http://localhost:3000");
            assertThat(call(chat)).isTrue();  // 챗봇 횟수는 ChatUsageGuard 담당
        }
    }

    @Test
    void groupsPaths() {
        assertThat(ApiRateLimitInterceptor.group("/api/stocks/005930/candles")).isEqualTo(ApiRateLimitInterceptor.Group.CHART);
        assertThat(ApiRateLimitInterceptor.group("/api/stocks/005930/intraday")).isEqualTo(ApiRateLimitInterceptor.Group.CHART);
        assertThat(ApiRateLimitInterceptor.group("/api/stocks/005930/news")).isEqualTo(ApiRateLimitInterceptor.Group.DEFAULT);
        assertThat(ApiRateLimitInterceptor.group("/api/company-events")).isEqualTo(ApiRateLimitInterceptor.Group.DEFAULT);
        assertThat(ApiRateLimitInterceptor.group("/api/chat")).isEqualTo(ApiRateLimitInterceptor.Group.CHAT);
        assertThat(ApiRateLimitInterceptor.group("/api/auth/login")).isEqualTo(ApiRateLimitInterceptor.Group.AUTH);
        assertThat(ApiRateLimitInterceptor.group("/api/rag/reports/1")).isNull();
    }

    @Test
    void zeroLimitTurnsOff() throws Exception {
        var off = new ApiRateLimitInterceptor(new ApiRateLimiter(() -> now),
                new ApiAbuseGuard(() -> now, 0, 0, 600_000, 600_000), 0, 0, 0, 0, 0);
        for (int i = 0; i < 100; i++) assertThat(call(off, browser("/api/stocks/005930/price", "10.0.0.1"))).isTrue();
    }

    @Test
    void cleanupRemovesIdleKeys() {
        var limiter = new ApiRateLimiter(() -> now);
        limiter.acquire("a", 5);
        limiter.acquire("b", 5);
        limiter.cleanup(now + 60_000);
        assertThat(limiter.trackedKeys()).isZero();
    }
}
