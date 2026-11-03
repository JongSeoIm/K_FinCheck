package com.example.stock_api.news;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.example.stock_api.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 네이버 뉴스 검색으로 기업명 관련 최신 기사를 가져옵니다.
 * naver.provider=developers (기본) : 네이버 개발자센터 검색 API (카드 등록 없음, 하루 25,000회 무료)
 * naver.provider=apihub            : NAVER API HUB (네이버 클라우드, 결제수단 등록 필요)
 * 두 방식은 응답 형식이 같고 호출 주소·인증 헤더만 다릅니다. 키는 서로 호환되지 않습니다.
 * 최신순 최대 10개, 중복 링크·제목 제거, 강조 태그(<b>) 정리, 같은 기업은 5분 동안 재사용.
 */
@Service
public class NaverNewsService {

    private static final Logger log = LoggerFactory.getLogger(NaverNewsService.class);
    private static final int MAX_ITEMS = 10;
    private static final long TTL_MS = 5 * 60_000L;
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    // NAVER API HUB 는 JSON 을 text/plain 으로 보내는 경우가 있어 문자열로 받은 뒤 직접 JSON 으로 읽습니다
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient rest;
    private final String clientId;
    private final String clientSecret;
    private final boolean apiHub;

    private record Cached(long at, List<Map<String, Object>> data) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public NaverNewsService(@Value("${naver.client-id:}") String clientId,
                            @Value("${naver.client-secret:}") String clientSecret,
                            @Value("${naver.provider:developers}") String provider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.apiHub = "apihub".equalsIgnoreCase(provider.trim());
        String baseUrl = apiHub ? "https://naverapihub.apigw.ntruss.com" : "https://openapi.naver.com";
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public List<Map<String, Object>> search(String companyName) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NAVER_NOT_CONFIGURED",
                    "네이버 API 키가 설정되지 않았습니다. application-local.properties 를 확인하세요.");
        }
        Cached hit = cache.get(companyName);
        if (hit != null && System.currentTimeMillis() - hit.at() < TTL_MS) return hit.data();

        Map<String, Object> res;
        try {
            String body = rest.get()
                    .uri(u -> u.path(apiHub ? "/search/v1/news" : "/v1/search/news.json")
                            .queryParam("query", companyName)
                            .queryParam("display", 30) // 중복을 걸러도 10개가 남도록 넉넉히 받음
                            .queryParam("sort", "date")
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .header(apiHub ? "X-NCP-APIGW-API-KEY-ID" : "X-Naver-Client-Id", clientId)
                    .header(apiHub ? "X-NCP-APIGW-API-KEY" : "X-Naver-Client-Secret", clientSecret)
                    .retrieve()
                    .body(String.class);
            res = parse(body);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 키는 헤더로만 보내므로 응답 본문·상태만 기록해도 키가 노출되지 않습니다
            log.warn("네이버 뉴스 응답 오류 ({}): {}", status, e.getResponseBodyAsString());
            String message = status == 401 || status == 403
                    ? "네이버 API 인증에 실패했습니다. Client ID·Secret 과 검색 API 사용 설정을 확인하세요."
                    : status == 429 ? "네이버 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
                    : "뉴스를 가져오지 못했습니다. (" + status + ")";
            throw new ApiException(HttpStatus.BAD_GATEWAY, "NAVER_ERROR", message);
        } catch (RestClientException e) {
            log.warn("네이버 뉴스 호출 실패 ({}): {}", apiHub ? "apihub" : "developers", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "NAVER_UNAVAILABLE", "네이버 뉴스 서버에 연결하지 못했습니다.");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        if (res != null && res.get("items") instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String title = clean(str(m.get("title")));
                String original = str(m.get("originallink"));
                String link = str(m.get("link"));
                String url = !original.isEmpty() ? original : link;
                if (title.isEmpty() || url.isEmpty()) continue;
                if (!seenLinks.add(url) || !seenTitles.add(title.replace(" ", ""))) continue; // 중복 제거

                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", url);
                n.put("title", title);
                n.put("description", clean(str(m.get("description"))));
                n.put("url", url);
                n.put("source", host(url));
                n.put("datetime", isoTime(str(m.get("pubDate"))));
                out.add(n);
                if (out.size() >= MAX_ITEMS) break;
            }
        }
        cache.put(companyName, new Cached(System.currentTimeMillis(), out));
        return out;
    }

    /** 응답 문자열을 JSON 으로 읽기. JSON 이 아니면 앞부분을 로그에 남기고 오류로 처리 */
    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            String head = body.length() > 200 ? body.substring(0, 200) + "…" : body;
            log.warn("네이버 뉴스 응답을 JSON 으로 읽지 못함: {}", head);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "NAVER_BAD_RESPONSE", "네이버 뉴스 응답 형식을 읽지 못했습니다.");
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    /** <b> 같은 강조 태그와 &quot; 같은 HTML 엔티티를 정리 */
    static String clean(String s) {
        String text = TAG.matcher(s).replaceAll("");
        text = text.replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        Matcher num = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (num.find()) {
            String code = num.group(1);
            int cp;
            try {
                cp = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            } catch (NumberFormatException e) {
                continue;
            }
            num.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        num.appendTail(sb);
        return sb.toString().replace("&amp;", "&").trim();
    }

    /** 기사 주소의 도메인 (예: www.hankyung.com → hankyung.com) */
    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.replaceFirst("^(www|m)\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** "Tue, 03 Nov 2026 10:20:00 +0900" → "2026-11-03T10:20+09:00" */
    private static String isoTime(String pubDate) {
        try {
            return ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}