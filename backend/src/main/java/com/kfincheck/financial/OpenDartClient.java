package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    public JsonNode disclosurePage(String corpCode, String start, String end, String type, int page) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new FinancialException(HttpStatus.SERVICE_UNAVAILABLE, "KEY_NOT_CONFIGURED",
                    "OpenDART API key is not configured.");
        }
        JsonNode body;
        try {
            body = client.get().uri(uri -> uri.path("/api/list.json")
                    .queryParam("crtfc_key", config.getApiKey())
                    .queryParam("corp_code", corpCode).queryParam("bgn_de", start).queryParam("end_de", end)
                    .queryParam("pblntf_ty", type).queryParam("page_no", page).queryParam("page_count", 100)
                    .build()).retrieve().body(JsonNode.class);
        } catch (Exception ignored) {
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_UNAVAILABLE",
                    "Could not connect to OpenDART.");
        }
        String status = body == null ? "" : body.path("status").asText();
        if ("013".equals(status)) return body;
        if (!"000".equals(status) || !body.path("list").isArray() || body.path("total_page").asInt() < 1) {
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_REJECTED",
                    "OpenDART disclosure search failed (status: " + (status.matches("[0-9]{3}") ? status : "unknown") + ").");
        }
        return body;
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

    /** 기간 안의 모든 종류 공시 목록 한 페이지(100건). 공시가 없으면 status 013 응답을 그대로 돌려준다. */
    public JsonNode disclosureList(String corpCode, String start, String end, int page) {
        requireKey();
        JsonNode body;
        try {
            body = client.get().uri(uri -> uri.path("/api/list.json")
                    .queryParam("crtfc_key", config.getApiKey())
                    .queryParam("corp_code", corpCode).queryParam("bgn_de", start).queryParam("end_de", end)
                    .queryParam("page_no", page).queryParam("page_count", 100).build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception ignored) {
            // 원본 예외에는 인증키가 담긴 요청 URL이 포함될 수 있으므로 전달하거나 기록하지 않는다.
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_UNAVAILABLE", "공시 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        String status = body == null ? "" : body.path("status").asText();
        if ("013".equals(status)) return body;
        if (!"000".equals(status) || !body.path("list").isArray()) throw rejected(status);
        return body;
    }

    /** 사업보고서(pblntf_detail_ty=A001) 최종본 목록. 최신 접수순. */
    public JsonNode annualReportFilings(String corpCode, String start, String end) {
        requireKey();
        JsonNode body;
        try {
            body = client.get().uri(uri -> uri.path("/api/list.json")
                    .queryParam("crtfc_key", config.getApiKey())
                    .queryParam("corp_code", corpCode).queryParam("bgn_de", start).queryParam("end_de", end)
                    .queryParam("pblntf_ty", "A").queryParam("pblntf_detail_ty", "A001")
                    .queryParam("last_reprt_at", "Y").queryParam("page_count", 10).build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception ignored) {
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_UNAVAILABLE", "공시 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        String status = body == null ? "" : body.path("status").asText();
        if ("013".equals(status)) throw new FinancialException(HttpStatus.NOT_FOUND, "NO_REPORT", "최근 사업보고서가 없습니다.");
        if (!"000".equals(status) || !body.path("list").isArray()) throw rejected(status);
        return body.path("list");
    }

    /** 공시서류원본파일(document.xml). 정상 응답은 ZIP 바이너리다. */
    public byte[] documentZip(String receiptNo) {
        return download("/api/document.xml", "rcept_no", receiptNo);
    }

    /** 전체 기업 고유번호 파일(corpCode.xml). 정상 응답은 ZIP 바이너리다. */
    public byte[] corpCodeZip() {
        return download("/api/corpCode.xml", null, null);
    }

    private static final Pattern STATUS = Pattern.compile("<status>(\\d{3})</status>|\"status\"\\s*:\\s*\"(\\d{3})\"");

    private byte[] download(String path, String name, String value) {
        requireKey();
        byte[] body;
        try {
            body = client.get().uri(uri -> {
                var builder = uri.path(path).queryParam("crtfc_key", config.getApiKey());
                if (name != null) builder.queryParam(name, value);
                return builder.build();
            }).retrieve().body(byte[].class);
        } catch (Exception ignored) {
            // 원본 예외에는 인증키가 담긴 요청 URL이 포함될 수 있으므로 전달하거나 기록하지 않는다.
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_UNAVAILABLE", "공시 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (body != null && body.length > 4 && body[0] == 'P' && body[1] == 'K') return body;
        // ZIP 이 아니면 status 가 담긴 오류 응답이다.
        String text = body == null ? "" : new String(body, 0, Math.min(body.length, 500), StandardCharsets.UTF_8);
        Matcher matcher = STATUS.matcher(text);
        String status = matcher.find() ? (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)) : "unknown";
        if ("013".equals(status) || "014".equals(status)) {
            throw new FinancialException(HttpStatus.NOT_FOUND, "NO_DOCUMENT", "공시 원문 파일이 없습니다.");
        }
        throw rejected(status);
    }

    private void requireKey() {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new FinancialException(HttpStatus.SERVICE_UNAVAILABLE, "KEY_NOT_CONFIGURED", "OpenDART 인증키 설정이 필요합니다.");
        }
    }

    private static FinancialException rejected(String status) {
        String message = switch (status) {
            case "010", "011", "012", "901" -> "OpenDART 인증키 또는 접근 설정을 확인해 주세요.";
            case "020" -> "공시 조회 한도를 초과했습니다. 나중에 다시 시도해 주세요.";
            default -> "공시 데이터를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        };
        return new FinancialException(HttpStatus.BAD_GATEWAY, "DART_REJECTED", message);
    }
}
