package com.hk.stock.market.kis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hk.stock.config.KisProperties;
import com.hk.stock.market.MarketDataException;

/**
 * KIS 인증
 * - 접근 토큰(REST용): /oauth2/tokenP, 약 24시간 유효 → 파일에 보관해 서버를 재시작해도 재사용 (발급 횟수 제한 대비)
 * - 접속키(WebSocket용): /oauth2/Approval (필드 이름이 appsecret 이 아니라 secretkey)
 */
public class KisAuthService {

	private static final Logger log = LoggerFactory.getLogger(KisAuthService.class);

	private final KisProperties props;
	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	private String accessToken;
	private Instant accessTokenExpiresAt;
	private String approvalKey;

	public KisAuthService(KisProperties props, RestClient restClient, ObjectMapper objectMapper) {
		this.props = props;
		this.restClient = restClient;
		this.objectMapper = objectMapper;
	}

	public synchronized String getAccessToken() {
		if (isValid(accessToken, accessTokenExpiresAt)) {
			return accessToken;
		}
		if (loadTokenFromFile()) {
			return accessToken;
		}
		issueAccessToken();
		return accessToken;
	}

	//토큰 만료 응답을 받았을 때 호출 → 다음 요청에서 새로 발급
	public synchronized void invalidateAccessToken() {
		accessToken = null;
		accessTokenExpiresAt = null;
		try {
			Files.deleteIfExists(Path.of(props.getTokenCacheFile()));
		} catch (IOException e) {
			log.warn("토큰 캐시 파일 삭제 실패: {}", e.getMessage());
		}
	}

	public synchronized String getApprovalKey(boolean forceNew) {
		if (approvalKey != null && !forceNew) {
			return approvalKey;
		}
		JsonNode body = post("/oauth2/Approval", Map.of(
				"grant_type", "client_credentials",
				"appkey", props.getAppKey(),
				"secretkey", props.getAppSecret()));
		String key = body.path("approval_key").asText(null);
		if (key == null || key.isBlank()) {
			throw new MarketDataException("KIS 실시간 접속키 발급 실패: " + body);
		}
		approvalKey = key;
		log.info("KIS 실시간 접속키 발급 완료");
		return approvalKey;
	}

	private void issueAccessToken() {
		JsonNode body = post("/oauth2/tokenP", Map.of(
				"grant_type", "client_credentials",
				"appkey", props.getAppKey(),
				"appsecret", props.getAppSecret()));
		String token = body.path("access_token").asText(null);
		if (token == null || token.isBlank()) {
			throw new MarketDataException("KIS 접근 토큰 발급 실패: " + body.path("error_description").asText(body.toString()));
		}
		long expiresIn = body.path("expires_in").asLong(86400);
		accessToken = token;
		accessTokenExpiresAt = Instant.now().plusSeconds(expiresIn);
		saveTokenToFile();
		log.info("KIS 접근 토큰 발급 완료 (만료: {})", accessTokenExpiresAt);
	}

	private JsonNode post(String path, Map<String, String> body) {
		try {
			return restClient.post()
					.uri(props.getRestUrl() + path)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
		} catch (RestClientException e) {
			throw new MarketDataException("KIS 인증 요청 실패(" + path + "): " + e.getMessage(), e);
		}
	}

	private boolean isValid(String token, Instant expiresAt) {
		//만료 10분 전부터는 새로 발급
		return token != null && expiresAt != null && Instant.now().plusSeconds(600).isBefore(expiresAt);
	}

	//다른 앱키로 받은 토큰을 잘못 쓰지 않도록 앱키 앞 6자리를 함께 저장
	private String appKeyHint() {
		String key = props.getAppKey();
		return key.length() > 6 ? key.substring(0, 6) : key;
	}

	private boolean loadTokenFromFile() {
		Path file = Path.of(props.getTokenCacheFile());
		if (!Files.exists(file)) {
			return false;
		}
		try {
			JsonNode saved = objectMapper.readTree(file.toFile());
			String token = saved.path("accessToken").asText(null);
			Instant expiresAt = Instant.ofEpochMilli(saved.path("expiresAt").asLong(0));
			if (appKeyHint().equals(saved.path("appKeyHint").asText()) && isValid(token, expiresAt)) {
				accessToken = token;
				accessTokenExpiresAt = expiresAt;
				log.info("저장된 KIS 접근 토큰 재사용 (만료: {})", expiresAt);
				return true;
			}
		} catch (IOException e) {
			log.warn("토큰 캐시 파일 읽기 실패: {}", e.getMessage());
		}
		return false;
	}

	private void saveTokenToFile() {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("appKeyHint", appKeyHint());
		node.put("accessToken", accessToken);
		node.put("expiresAt", accessTokenExpiresAt.toEpochMilli());
		try {
			objectMapper.writeValue(Path.of(props.getTokenCacheFile()).toFile(), node);
		} catch (IOException e) {
			log.warn("토큰 캐시 파일 저장 실패: {}", e.getMessage());
		}
	}
}
