package com.hk.stock.market.kis;

import java.util.Map;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.config.KisProperties;
import com.hk.stock.market.MarketDataException;

/**
 * KIS 시세 조회(GET) 공통 처리
 * - 공통 헤더(토큰·앱키·tr_id) 설정
 * - 초당 호출 제한을 넘지 않도록 요청 사이 최소 간격 유지 (kis.min-interval-ms)
 * - 응답 rt_cd 가 "0"이 아니면 오류로 처리
 * - 토큰 만료(EGW00123)면 1회 재발급 후 재시도, 호출 한도 초과(EGW00201)면 잠시 쉬었다가 최대 4회 재시도
 */
public class KisRestClient {

	//화면을 처음 열 때 차트·투자의견·실적 요청이 한꺼번에 몰리므로 한도 초과 시 넉넉히 재시도한다
	private static final int RATE_LIMIT_RETRIES = 4;

	private final KisProperties props;
	private final KisAuthService auth;
	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private long lastCallAt = 0;

	public KisRestClient(KisProperties props, KisAuthService auth, RestClient restClient, ObjectMapper objectMapper) {
		this.props = props;
		this.auth = auth;
		this.restClient = restClient;
		this.objectMapper = objectMapper;
	}

	public JsonNode get(String path, String trId, Map<String, String> params) {
		boolean tokenRenewed = false;
		for (int attempt = 0; ; attempt++) {
			try {
				return call(path, trId, params);
			} catch (TokenExpiredException e) {
				if (tokenRenewed) throw new MarketDataException("KIS 접근 토큰이 계속 만료 응답을 받습니다(" + trId + ")");
				tokenRenewed = true;
				auth.invalidateAccessToken();
			} catch (RateLimitException e) {
				if (attempt >= RATE_LIMIT_RETRIES) throw new MarketDataException("KIS 초당 호출 한도 초과(" + trId + ")");
				sleep(1000L * (attempt + 1));
			}
		}
	}

	private JsonNode call(String path, String trId, Map<String, String> params) {
		throttle();
		JsonNode body;
		try {
			body = restClient.get()
					.uri(props.getRestUrl() + path, uriBuilder -> addParams(uriBuilder, params))
					.header("authorization", "Bearer " + auth.getAccessToken())
					.header("appkey", props.getAppKey())
					.header("appsecret", props.getAppSecret())
					.header("tr_id", trId)
					.header("custtype", "P")
					.retrieve()
					.body(JsonNode.class);
		} catch (RestClientResponseException e) {
			//토큰 만료(EGW00123) 등은 오류 응답 본문에 담겨 온다
			JsonNode error = readJson(e.getResponseBodyAsString());
			checkRetryable(error);
			throw new MarketDataException("KIS 요청 실패(" + trId + "): " + error.path("msg1").asText(e.getMessage()), e);
		} catch (RestClientException e) {
			throw new MarketDataException("KIS 요청 실패(" + trId + "): " + e.getMessage(), e);
		}

		if (body == null) {
			throw new MarketDataException("KIS 응답이 비어 있습니다(" + trId + ")");
		}
		if (!"0".equals(body.path("rt_cd").asText())) {
			checkRetryable(body);
			throw new MarketDataException("KIS 오류(" + trId + "): [" + body.path("msg_cd").asText() + "] " + body.path("msg1").asText());
		}
		return body;
	}

	private java.net.URI addParams(UriBuilder builder, Map<String, String> params) {
		params.forEach(builder::queryParam);
		return builder.build();
	}

	private void checkRetryable(JsonNode error) {
		String code = error.path("msg_cd").asText();
		if ("EGW00123".equals(code)) throw new TokenExpiredException();
		if ("EGW00201".equals(code)) throw new RateLimitException();
	}

	private synchronized void throttle() {
		sleep(lastCallAt + props.getMinIntervalMs() - System.currentTimeMillis());
		lastCallAt = System.currentTimeMillis();
	}

	private static void sleep(long millis) {
		if (millis <= 0) return;
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private JsonNode readJson(String text) {
		try {
			return objectMapper.readTree(text == null || text.isBlank() ? "{}" : text);
		} catch (Exception e) {
			return objectMapper.createObjectNode();
		}
	}

	private static class TokenExpiredException extends RuntimeException {
	}

	private static class RateLimitException extends RuntimeException {
	}
}
