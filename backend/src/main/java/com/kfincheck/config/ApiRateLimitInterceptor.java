package com.kfincheck.config;

import java.io.IOException;
import java.util.Set;

import com.kfincheck.auth.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 로그인한 계정의 API 반복 호출(자동 호출) 대응. 3단계로 막는다.
 * <ol>
 *   <li>1분 한도: API 묶음별 1분 호출 수. 넘으면 429 와 Retry-After.</li>
 *   <li>하루 한도: 계정별 하루 전체 호출 수. 1분 한도를 지키면서 하루 종일 부르는 경우를 막는다.</li>
 *   <li>일시 정지: 10분 안에 1분 한도로 N번 넘게 막히면 10분 동안 조회·챗봇 API 를 모두 막는다.</li>
 * </ol>
 * 로그인했으면 계정별, 아니면 IP별로 센다. 챗봇은 횟수·토큰을 ChatUsageGuard 가 따로 세고, 여기서는 정지 여부만 확인한다.
 * 로그인·회원가입(/api/auth)과 AI 서버 전용(/api/rag), 상태 확인(/api/health)은 막지 않는다.
 * AI 서버가 챗봇 답변을 만들려고 부르는 조회(같은 PC + Origin 헤더 없음)도 세지 않는다. 이미 챗봇 한도 안에서만 일어난다.
 * 값은 application.yml 의 api.limit.* (환경변수 API_LIMIT_*)로 바꾸고, 0 이면 끈다.
 */
@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitInterceptor.class);
    private static final Set<String> LOCAL = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    enum Group { REPORT, PRICE, CHART, DEFAULT, CHAT }

    private final ApiRateLimiter limiter;
    private final ApiAbuseGuard abuse;
    private final int reportLimit;
    private final int priceLimit;
    private final int chartLimit;
    private final int defaultLimit;

    @Autowired
    public ApiRateLimitInterceptor(
            @Value("${api.limit.report-per-minute:5}") int reportLimit,
            @Value("${api.limit.price-per-minute:60}") int priceLimit,
            @Value("${api.limit.chart-per-minute:40}") int chartLimit,
            @Value("${api.limit.default-per-minute:60}") int defaultLimit,
            @Value("${api.limit.per-day:20000}") int dailyLimit,
            @Value("${api.limit.suspend-after-violations:20}") int suspendAfter,
            @Value("${api.limit.suspend-minutes:10}") int suspendMinutes) {
        this(new ApiRateLimiter(), new ApiAbuseGuard(System::currentTimeMillis, dailyLimit, suspendAfter,
                        10 * 60_000L, suspendMinutes * 60_000L),
                reportLimit, priceLimit, chartLimit, defaultLimit);
    }

    ApiRateLimitInterceptor(ApiRateLimiter limiter, ApiAbuseGuard abuse,
                            int reportLimit, int priceLimit, int chartLimit, int defaultLimit) {
        this.limiter = limiter;
        this.abuse = abuse;
        this.reportLimit = reportLimit;
        this.priceLimit = priceLimit;
        this.chartLimit = chartLimit;
        this.defaultLimit = defaultLimit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;   // CORS 사전 요청
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Group group = group(path);
        if (group == null || fromAiServer(request)) return true;
        String who = who(request);

        // 3단계: 정지 중이면 챗봇까지 모두 막는다.
        long suspended = abuse.suspendedSeconds(who);
        if (suspended > 0) return reject(response, suspended, "반복된 과다 호출로 이용이 일시 정지되었습니다. "
                + minutesText(suspended) + " 후 다시 이용해 주세요.");
        if (group == Group.CHAT) return true;   // 챗봇 횟수·토큰은 ChatUsageGuard 가 센다.

        // 1단계: 1분 한도
        int limit = limit(group);
        long retryAfter = limiter.acquire(who + "|" + group, limit);
        if (retryAfter > 0) {
            if (abuse.recordViolation(who)) {
                log.warn("API 이용 일시 정지: {} ({}분, 1분 한도 반복 초과)", who, abuse.suspendMinutes());
                return reject(response, abuse.suspendedSeconds(who), "반복된 과다 호출로 이용이 일시 정지되었습니다. "
                        + abuse.suspendMinutes() + "분 후 다시 이용해 주세요.");
            }
            log.warn("API 호출 한도 초과: {} {} (1분 {}회)", who, group, limit);
            return reject(response, retryAfter, "요청이 너무 많습니다. " + retryAfter + "초 후 다시 시도해 주세요.");
        }

        // 2단계: 하루 한도
        if (!abuse.countDaily(who)) {
            log.warn("API 하루 한도 초과: {} (하루 {}회)", who, abuse.dailyLimit());
            return reject(response, secondsUntilMidnight(), "오늘 이용 한도를 모두 사용했습니다. 내일 다시 이용해 주세요.");
        }
        return true;
    }

    /** 막지 않는 API 는 null. 챗봇은 CHAT(정지 여부만 확인). */
    static Group group(String path) {
        if (!path.startsWith("/api/")) return null;
        if (path.startsWith("/api/auth/") || path.startsWith("/api/rag/") || path.startsWith("/api/health")) return null;
        if (path.equals("/api/chat") || path.startsWith("/api/chat/")) return Group.CHAT;
        if (path.matches("/api/stocks/[^/]+/business-report(/.*)?")) return Group.REPORT;
        if (path.matches("/api/stocks/[^/]+/price")) return Group.PRICE;
        if (path.matches("/api/stocks/[^/]+/(candles|intraday)")) return Group.CHART;
        return Group.DEFAULT;
    }

    private int limit(Group group) {
        return switch (group) {
            case REPORT -> reportLimit;
            case PRICE -> priceLimit;
            case CHART -> chartLimit;
            default -> defaultLimit;
        };
    }

    private static boolean reject(HttpServletResponse response, long retryAfter, String message) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter)));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }

    private static String minutesText(long seconds) {
        return seconds >= 60 ? ((seconds + 59) / 60) + "분" : seconds + "초";
    }

    private static long secondsUntilMidnight() {
        var now = java.time.ZonedDateTime.now(ApiAbuseGuard.KST);
        return java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ApiAbuseGuard.KST)).toSeconds();
    }

    static boolean fromAiServer(HttpServletRequest request) {
        return LOCAL.contains(request.getRemoteAddr()) && request.getHeader("Origin") == null;
    }

    private static String who(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountService.User user) return "user:" + user.userId();
        return "ip:" + request.getRemoteAddr();
    }
}
