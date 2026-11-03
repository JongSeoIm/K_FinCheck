package com.example.stock_api.earnings;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 종목별 최근 실적 (OpenDART 주요계정: 매출액 / 영업이익 / 당기순이익).
 * - 종목코드(6자리) → DART 고유번호(corp_code)는 DART 의 corpCode.xml 을 처음 한 번 받아 메모리에 보관합니다.
 * - 최신 보고서부터 거슬러 올라가며 최대 4개를 모읍니다. 결과는 1시간 캐시합니다.
 * - 키는 application-local.properties 의 dart.api-key 에만 둡니다.
 */
@Service
public class EarningsService {

    private static final Logger LOG = LoggerFactory.getLogger(EarningsService.class);
    private static final long CACHE_SECONDS = 3600;

    // 보고서 종류: 11013 1분기, 11012 반기(2분기), 11014 3분기, 11011 사업보고서(연간)
    private record Report(int year, String code, String label) {}

    private final String apiKey;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private Map<String, String> corpCodes;

    private record Cached(Instant at, List<Item> items) {}

    /** 금액 단위는 원. 값이 없으면 null. *Yoy 는 전년 동기 대비 증감률(%). */
    public record Item(String period, Long revenue, Long operatingProfit, Long netIncome,
                       Double revenueYoy, Double operatingYoy, Double netIncomeYoy) {}

    public static class EarningsException extends RuntimeException {
        private final HttpStatus status;

        public EarningsException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    public EarningsService(@Value("${dart.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.client = RestClient.builder()
                .baseUrl("https://opendart.fss.or.kr")
                .requestFactory(factory)
                .build();
    }

    public synchronized List<Item> latest(String stockCode) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new EarningsException(HttpStatus.NOT_FOUND, "국내 종목코드(6자리)만 지원합니다.");
        }
        Cached hit = cache.get(stockCode);
        if (hit != null && hit.at().plusSeconds(CACHE_SECONDS).isAfter(Instant.now())) {
            return hit.items();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new EarningsException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenDART 키가 설정되지 않았습니다. application-local.properties 의 dart.api-key 를 확인하세요.");
        }
        String corpCode = corpCodeOf(stockCode);
        if (corpCode == null) {
            throw new EarningsException(HttpStatus.NOT_FOUND, "DART 에 등록된 기업을 찾지 못했습니다.");
        }

        List<Item> items = new ArrayList<>();
        for (Report report : candidates()) {
            if (items.size() >= 4) {
                break;
            }
            Item item = fetch(corpCode, report);
            if (item != null) {
                items.add(item);
            }
        }
        cache.put(stockCode, new Cached(Instant.now(), items));
        return items;
    }

    /** 최신 보고서부터 과거 순서. 아직 제출 전인 보고서는 조회 결과가 없어 자동으로 건너뜁니다. */
    private static List<Report> candidates() {
        List<Report> list = new ArrayList<>();
        int thisYear = LocalDate.now().getYear();
        for (int year = thisYear; year >= thisYear - 2; year--) {
            list.add(new Report(year, "11011", year + " 연간"));
            list.add(new Report(year, "11014", year + " 3분기"));
            list.add(new Report(year, "11012", year + " 2분기"));
            list.add(new Report(year, "11013", year + " 1분기"));
        }
        return list;
    }

    private Item fetch(String corpCode, Report report) {
        JsonNode body;
        try {
            String response = client.get()
                    .uri(uri -> uri.path("/api/fnlttSinglAcnt.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", report.year())
                            .queryParam("reprt_code", report.code())
                            .build())
                    .retrieve()
                    .body(String.class);
            body = mapper.readTree(response);
        } catch (Exception e) {
            LOG.warn("DART 통신 실패: {}", e.getClass().getSimpleName());
            throw new EarningsException(HttpStatus.BAD_GATEWAY, "DART 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }

        String status = body.path("status").asText("");
        if ("013".equals(status)) {
            return null; // 해당 기간 보고서 없음
        }
        if (!"000".equals(status)) {
            LOG.warn("DART 응답 status={}", status);
            String message = switch (status) {
                case "010", "011", "012" -> "OpenDART 인증키를 확인해 주세요.";
                case "020" -> "DART 호출 한도를 넘었습니다. 잠시 후 다시 시도해 주세요.";
                default -> "DART 에서 자료를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
            };
            throw new EarningsException(HttpStatus.BAD_GATEWAY, message);
        }

        JsonNode list = body.path("list");
        Long[] revenue = amounts(list, "매출액");
        Long[] operating = amounts(list, "영업이익");
        Long[] net = amounts(list, "당기순이익");
        if (revenue[0] == null && operating[0] == null && net[0] == null) {
            return null;
        }
        return new Item(report.label(), revenue[0], operating[0], net[0],
                growth(revenue), growth(operating), growth(net));
    }

    /** [당기, 전기(전년 동기)] 금액. 연결(CFS)을 우선하고 없으면 별도(OFS). */
    private static Long[] amounts(JsonNode list, String account) {
        JsonNode found = null;
        for (JsonNode row : list) {
            if (!row.path("account_nm").asText("").startsWith(account)) {
                continue;
            }
            if ("CFS".equals(row.path("fs_div").asText(""))) {
                found = row;
                break;
            }
            if (found == null) {
                found = row;
            }
        }
        if (found == null) {
            return new Long[] {null, null};
        }
        return new Long[] {parse(found.path("thstrm_amount").asText("")), parse(found.path("frmtrm_amount").asText(""))};
    }

    static Long parse(String value) {
        String digits = value.replace(",", "").trim();
        if (digits.isEmpty() || "-".equals(digits)) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double growth(Long[] pair) {
        if (pair[0] == null || pair[1] == null || pair[1] == 0L) {
            return null;
        }
        return (pair[0] - pair[1]) * 100.0 / Math.abs(pair[1]);
    }

    private String corpCodeOf(String stockCode) {
        if (corpCodes == null) {
            corpCodes = loadCorpCodes();
        }
        return corpCodes.get(stockCode);
    }

    /** DART corpCode.xml(zip) 을 받아 {종목코드: 고유번호} 로 만듭니다. 상장사만 담습니다. */
    private Map<String, String> loadCorpCodes() {
        byte[] zip;
        try {
            zip = client.get()
                    .uri(uri -> uri.path("/api/corpCode.xml").queryParam("crtfc_key", apiKey).build())
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            LOG.warn("DART 기업코드 다운로드 실패: {}", e.getClass().getSimpleName());
            throw new EarningsException(HttpStatus.BAD_GATEWAY, "DART 기업코드를 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        // 정상 응답은 zip("PK"로 시작), 키 오류 등은 짧은 XML 로 옵니다.
        if (zip == null || zip.length < 4 || zip[0] != 'P' || zip[1] != 'K') {
            throw new EarningsException(HttpStatus.BAD_GATEWAY, "OpenDART 인증키를 확인해 주세요.");
        }
        Map<String, String> map = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            if (in.getNextEntry() == null) {
                throw new EarningsException(HttpStatus.BAD_GATEWAY, "DART 기업코드 파일이 비어 있습니다.");
            }
            readCorpXml(in, map);
        } catch (EarningsException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("DART 기업코드 해석 실패: {}", e.getClass().getSimpleName());
            throw new EarningsException(HttpStatus.BAD_GATEWAY, "DART 기업코드를 해석하지 못했습니다.");
        }
        return map;
    }

    private static void readCorpXml(InputStream in, Map<String, String> map) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        String tag = "";
        String corp = "";
        String stock = "";
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                tag = reader.getLocalName();
                if ("list".equals(tag)) {
                    corp = "";
                    stock = "";
                }
            } else if (event == XMLStreamConstants.CHARACTERS) {
                String text = reader.getText().trim();
                if ("corp_code".equals(tag)) {
                    corp += text;
                } else if ("stock_code".equals(tag)) {
                    stock += text;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if ("list".equals(reader.getLocalName()) && stock.matches("\\d{6}") && !corp.isEmpty()) {
                    map.put(stock, corp);
                }
                tag = "";
            }
        }
        reader.close();
    }
}
