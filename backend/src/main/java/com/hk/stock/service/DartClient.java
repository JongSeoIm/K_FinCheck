package com.hk.stock.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import com.hk.stock.dtos.DisclosureDto;

//OpenDART 단일회사 주요계정·공시 목록 조회 (API 키는 서버에서만 사용)
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
		JsonNode body = checked(request(props.getUrl() + "?crtfc_key={key}&corp_code={corp}&bsns_year={year}&reprt_code={code}",
				props.getApiKey(), corpCode, bsnsYear, reprtCode));
		return body == null ? List.of() : DartParsers.rows(body);
	}

	//최근 days 일의 공시 (최신순 limit 개)
	public List<DisclosureDto> fetchDisclosures(String corpCode, LocalDate today, int days, int limit) {
		DateTimeFormatter format = DateTimeFormatter.BASIC_ISO_DATE;
		JsonNode body = checked(request(props.getListUrl() + "?crtfc_key={key}&corp_code={corp}&bgn_de={from}&end_de={to}&page_count=100",
				props.getApiKey(), corpCode, today.minusDays(days).format(format), today.format(format)));
		return body == null ? List.of() : DartParsers.disclosures(body, limit);
	}

	private JsonNode request(String uri, Object... variables) {
		if (!props.isConfigured()) {
			throw new DartApiException(HttpStatus.SERVICE_UNAVAILABLE, "서버에 DART_API_KEY 가 설정되지 않았습니다.");
		}
		try {
			byte[] raw = restClient.get().uri(uri, variables).retrieve().body(byte[].class);
			return raw == null ? null : objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
		} catch (RestClientException | java.io.IOException e) {
			log.warn("OpenDART 요청 실패: {}", e.getClass().getSimpleName());
			throw new DartApiException(HttpStatus.BAD_GATEWAY, "OpenDART에서 자료를 가져오지 못했습니다.");
		}
	}

	//정상이면 응답, 조회된 자료가 없으면(013) null, 그 밖에는 예외
	private JsonNode checked(JsonNode body) {
		if (body == null) {
			throw new DartApiException(HttpStatus.BAD_GATEWAY, "OpenDART 응답이 비어 있습니다.");
		}

		String status = DartParsers.status(body);
		switch (status) {
			case "000":
				return body;
			case "013":
				return null;
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
