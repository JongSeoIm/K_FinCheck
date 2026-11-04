package com.hk.stock.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.config.DartProperties;

//OpenDART 단일회사 주요계정 조회 (API 키는 서버에서만 사용)
@Component
public class DartClient {

	private static final Logger log = LoggerFactory.getLogger(DartClient.class);

	private final DartProperties props;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public DartClient(DartProperties props, ObjectMapper objectMapper, RestClient.Builder builder) {
		this.props = props;
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5_000);
		factory.setReadTimeout(10_000);
		this.restClient = builder.requestFactory(factory).build();
	}

	//아직 공시되지 않은 보고서면 빈 목록 (status 013)
	public List<DartParsers.Row> fetchMainAccounts(String corpCode, String bsnsYear, String reprtCode) {
		if (!props.isConfigured()) {
			throw new DartApiException(HttpStatus.SERVICE_UNAVAILABLE, "서버에 DART_API_KEY 가 설정되지 않았습니다.");
		}
		JsonNode body;
		try {
			byte[] raw = restClient.get()
					.uri(props.getUrl() + "?crtfc_key={key}&corp_code={corp}&bsns_year={year}&reprt_code={code}",
							props.getApiKey(), corpCode, bsnsYear, reprtCode)
					.retrieve()
					.body(byte[].class);
			body = raw == null ? null : objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
		} catch (RestClientException | java.io.IOException e) {
			log.warn("OpenDART 요청 실패: {}", e.getMessage());
			throw new DartApiException(HttpStatus.BAD_GATEWAY, "OpenDART에서 실적을 가져오지 못했습니다.");
		}
		if (body == null) {
			throw new DartApiException(HttpStatus.BAD_GATEWAY, "OpenDART 응답이 비어 있습니다.");
		}

		String status = DartParsers.status(body);
		switch (status) {
			case "000":
				return DartParsers.rows(body);
			case "013":
				return List.of();
			case "010", "011", "012", "901":
				log.warn("OpenDART 키 오류: {} {}", status, DartParsers.message(body));
				throw new DartApiException(HttpStatus.SERVICE_UNAVAILABLE,
						"OpenDART 인증키를 사용할 수 없습니다. DART_API_KEY 를 확인해 주세요. (" + status + " " + DartParsers.message(body) + ")");
			case "020":
				throw new DartApiException(HttpStatus.TOO_MANY_REQUESTS, "OpenDART 하루 호출 한도를 초과했습니다.");
			default:
				log.warn("OpenDART 오류: {} {}", status, DartParsers.message(body));
				throw new DartApiException(HttpStatus.BAD_GATEWAY, "OpenDART 오류입니다. (" + status + " " + DartParsers.message(body) + ")");
		}
	}

	public static class DartApiException extends RuntimeException {
		private final HttpStatus status;

		public DartApiException(HttpStatus status, String message) {
			super(message);
			this.status = status;
		}

		public HttpStatus getStatus() {
			return status;
		}
	}
}
