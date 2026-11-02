package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenDartClient {
    private final OpenDartConfig config;
    private final RestClient client;

    public OpenDartClient(OpenDartConfig config, RestClient.Builder builder) {
        this.config = config;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        client = builder.baseUrl("https://opendart.fss.or.kr")
                .requestFactory(factory).build();
    }

    public JsonNode annualReport(String corpCode, int year) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new FinancialException(HttpStatus.SERVICE_UNAVAILABLE, "KEY_NOT_CONFIGURED",
                    "OpenDART 인증키 설정이 필요합니다.");
        }
        JsonNode body;
        try {
            body = client.get().uri(uri -> uri.path("/api/fnlttSinglAcntAll.json")
                    .queryParam("crtfc_key", config.getApiKey())
                    .queryParam("corp_code", corpCode).queryParam("bsns_year", year)
                    .queryParam("reprt_code", "11011").queryParam("fs_div", "CFS").build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception ignored) {
            // 원본 예외에는 인증키가 담긴 요청 URL이 포함될 수 있으므로 전달하거나 기록하지 않는다.
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_UNAVAILABLE",
                    "공시 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        String status = body == null ? "" : body.path("status").asText();
        if ("013".equals(status)) {
            throw new FinancialException(HttpStatus.NOT_FOUND, "NO_REPORT",
                    "해당 연도의 연결 사업보고서가 없습니다.");
        }
        if (!"000".equals(status)) {
            String message = switch (status) {
                case "010", "011", "012", "901" -> "OpenDART 인증키 또는 접근 설정을 확인해 주세요.";
                case "020" -> "공시 조회 한도를 초과했습니다. 나중에 다시 시도해 주세요.";
                default -> "공시 데이터를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            };
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_REJECTED", message);
        }
        if (!body.path("list").isArray() || body.path("list").isEmpty()) {
            throw new FinancialException(HttpStatus.NOT_FOUND, "NO_REPORT", "조회할 실적 자료가 없습니다.");
        }
        return body.path("list");
    }
}
