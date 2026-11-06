package com.hk.stock.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.config.NaverNewsProperties;
import com.hk.stock.dtos.NewsDto;
import com.hk.stock.dtos.StockDto;

/**
 * 종목 관련 뉴스 (네이버 클라우드 NAVER API HUB - 검색 API 뉴스)
 * - 실습(14_realtime_stocweb)처럼 API 키는 서버에서만 사용하고, 브라우저는 우리 서버만 호출한다.
 * - 종목명으로 최신순 검색 → 태그·중복 제거 → 종목 기사 / 기타로 나눠 각각 최대 NEWS_LIMIT 건
 * - 같은 종목은 naver.news.cache-seconds 동안 저장해 둔 결과를 돌려준다 (일일 호출 한도 보호)
 */
@Service
public class NewsService {

	private static final Logger log = LoggerFactory.getLogger(NewsService.class);

	public static final int NEWS_LIMIT = 10;
	private static final int FETCH_SIZE = 100;	//걸러낸 뒤에도 종목 기사가 NEWS_LIMIT 건 남도록 최대치(100)로 받는다

	//기사 제목에서 함께 찾을 종목 줄임말 (종목명·종목코드는 항상 포함)
	private static final Map<String, List<String>> ALIASES = Map.of(
			"005930", List.of("삼전", "만전자", "이재용"),
			"000660", List.of("하이닉스", "하닉", "삼전닉스", "만닉스"));

	private final NaverNewsProperties props;
	private final StockService stockService;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public NewsService(NaverNewsProperties props, StockService stockService,
			ObjectMapper objectMapper, RestClient.Builder builder) {
		this.props = props;
		this.stockService = stockService;
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5_000);
		factory.setReadTimeout(10_000);
		this.restClient = builder.requestFactory(factory).build();
	}

	public List<NewsDto> getNews(String symbol) {
		StockDto stock = stockService.getStock(symbol);	//지원하지 않는 종목이면 404
		if (!props.isConfigured()) {
			throw new NewsApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"서버에 NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 이 설정되지 않았습니다.");
		}

		Cached cached = cache.get(symbol);
		if (cached != null && System.currentTimeMillis() - cached.fetchedAt < props.getCacheSeconds() * 1000L) {
			return cached.news;
		}

		List<NewsDto> news = NewsParsers.items(search(stock.getName()), keywords(stock), NEWS_LIMIT);
		cache.put(symbol, new Cached(news, System.currentTimeMillis()));
		return news;
	}

	static List<String> keywords(StockDto stock) {
		List<String> words = new ArrayList<>(List.of(stock.getName(), stock.getSymbol()));
		words.addAll(ALIASES.getOrDefault(stock.getSymbol(), List.of()));
		return words;
	}

	private JsonNode search(String query) {
		try {
			//API HUB는 JSON을 Content-Type: text/plain 으로 보내므로 문자열로 받은 뒤 직접 JSON으로 읽는다
			byte[] raw = restClient.get()
					.uri(props.getUrl() + "?query={q}&display={n}&sort=date&format=json", query, FETCH_SIZE)
					.header("X-NCP-APIGW-API-KEY-ID", props.getClientId())
					.header("X-NCP-APIGW-API-KEY", props.getClientSecret())
					.retrieve()
					.body(byte[].class);
			JsonNode body = raw == null ? null : objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
			if (body == null || !body.path("items").isArray()) {
				throw new NewsApiException(HttpStatus.BAD_GATEWAY, "네이버 뉴스 응답 형식이 올바르지 않습니다.");
			}
			return body;
		} catch (RestClientResponseException e) {
			throw classify(e);
		} catch (RestClientException | java.io.IOException e) {
			log.warn("네이버 뉴스 요청 실패: {}", e.getMessage());
			throw new NewsApiException(HttpStatus.BAD_GATEWAY, "뉴스를 불러오지 못했습니다. (" + e.getClass().getSimpleName() + ")");
		}
	}

	//NAVER API HUB 오류 응답 분류 (실습 classifyFinnhubError 와 같은 역할)
	private NewsApiException classify(RestClientResponseException e) {
		String code = "";
		String upstreamMessage = "";
		try {
			//API HUB 오류 본문: {"error":{"errorCode":401,"message":"..."}}
			JsonNode error = objectMapper.readTree(e.getResponseBodyAsString(StandardCharsets.UTF_8)).path("error");
			code = error.path("errorCode").asText("");
			upstreamMessage = error.path("message").asText("");
		} catch (Exception ignored) {
			//본문이 JSON이 아니면 상태 코드로만 판단
		}
		int status = e.getStatusCode().value();
		log.warn("네이버 뉴스 오류: {} {} {}", status, code, upstreamMessage);
		if (status == 429) {
			return new NewsApiException(HttpStatus.TOO_MANY_REQUESTS, "네이버 뉴스 API 하루 호출 한도를 초과했습니다.");
		}
		if (status == 401) {
			//예: "요청한 API가 이 Application에서 활성화되어 있지 않습니다." → 콘솔에서 Application에 검색 API 추가 필요
			String reason = upstreamMessage.isBlank() ? "" : " (네이버 응답: " + upstreamMessage + ")";
			return new NewsApiException(HttpStatus.UNAUTHORIZED,
					"NAVER API HUB 인증 실패: Client ID / Secret 과 Application의 '검색' API 활성화를 확인해 주세요." + reason);
		}
		if (status == 400 || status == 403) {
			return new NewsApiException(HttpStatus.BAD_GATEWAY, "네이버 뉴스 요청 형식 오류입니다. (" + status + " " + code + ")");
		}
		return new NewsApiException(HttpStatus.BAD_GATEWAY, "뉴스를 불러오지 못했습니다.");
	}

	private record Cached(List<NewsDto> news, long fetchedAt) {
	}

	public static class NewsApiException extends RuntimeException {
		private final HttpStatus status;

		public NewsApiException(HttpStatus status, String message) {
			super(message);
			this.status = status;
		}

		public HttpStatus getStatus() {
			return status;
		}
	}
}
