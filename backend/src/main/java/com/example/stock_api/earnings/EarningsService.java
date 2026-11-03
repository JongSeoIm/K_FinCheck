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
import java.util.function.Supplier;
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
 * 종목별 연간 실적 (OpenDART 사업보고서 주요계정: 매출액 / 영업이익 / 당기순이익).
 * - 가장 최근 사업보고서 1건의 당기·전기 금액을 돌려줍니다. (연결 기준 우선, 없으면 별도)
 * - 종목코드(6자리) → DART 고유번호는 DART corpCode.xml 을 처음 한 번 받아 메모리에 보관합니다.
 * - 결과는 1시간 캐시합니다. 키는 application-local.properties 의 dart.api-key 에만 둡니다.
 */
@Service
public class EarningsService {

    private static final Logger LOG = LoggerFactory.getLogger(EarningsService.class);
    private static final long CACHE_SECONDS = 3600;
    private static final String ANNUAL_REPORT = "11011"; // 사업보고서
    private static final String[][] ACCOUNTS = {
            {"revenue", "매출액"}, {"operatingProfit", "영업이익"}, {"netIncome", "당기순이익"}};

    private final String apiKey;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private Map<String, String> corpCodes;
    private final EarningsStore store;
    private volatile long dbRetryAt = 0L; // DB 가 안 될 때 이 시각까지는 DB 를 건너뜁니다

    private record Cached(Instant at, Annual annual) {}

    /** 금액 단위는 원. 값이 없으면 null (0으로 바꾸지 않음). */
    public record Line(String key, String label, Long current, Long previous, Double changePercent, String status) {}

    /** basis: "연결" 또는 "별도". dartUrl 은 DART 원본 공시 주소. */
    public record Annual(int businessYear, int previousYear, String basis, String unit,
                         String receiptNo, String dartUrl, List<Line> lines) {}

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

    public EarningsService(@Value("${dart.api-key:}") String apiKey, EarningsStore store) {
        this.apiKey = apiKey;
        this.store = store;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.client = RestClient.builder()
                .baseUrl("https://opendart.fss.or.kr")
                .requestFactory(factory)
                .build();
    }

    public synchronized Annual latest(String stockCode) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new EarningsException(HttpStatus.NOT_FOUND, "국내 종목코드(6자리)만 지원합니다.");
        }
        Cached hit = cache.get(stockCode);
        if (hit != null && hit.at().plusSeconds(CACHE_SECONDS).isAfter(Instant.now())) {
            return hit.annual();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new EarningsException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenDART 키가 설정되지 않았습니다. application-local.properties 의 dart.api-key 를 확인하세요.");
        }
        String corpCode = corpCodeOf(stockCode);
        if (corpCode == null) {
            throw new EarningsException(HttpStatus.NOT_FOUND, "DART 에 등록된 기업을 찾지 못했습니다.");
        }

        // 1) DB 에 1시간 이내에 수집한 자료가 있으면 그대로 사용합니다.
        Annual saved = db(() -> store.findFresh(stockCode));
        if (saved != null) {
            LOG.info("실적: DB 자료 재사용 {}", stockCode);
            cache.put(stockCode, new Cached(Instant.now(), saved));
            return saved;
        }

        // 2) 없거나 오래됐으면 DART 에서 가장 최근 사업보고서부터 최대 3개 사업연도를 거슬러 올라가며 찾습니다.
        int thisYear = LocalDate.now().getYear();
        for (int year = thisYear; year >= thisYear - 2; year--) {
            Fetched fetched = fetch(corpCode, year);
            if (fetched != null) {
                saveToDb(stockCode, corpCode, fetched);
                cache.put(stockCode, new Cached(Instant.now(), fetched.annual()));
                return fetched.annual();
            }
        }
        throw new EarningsException(HttpStatus.NOT_FOUND, "조회할 수 있는 사업보고서가 없습니다.");
    }

    private record Fetched(Annual annual, List<EarningsStore.ValueRow> rows) {}

    /** DB 작업을 실행합니다. DB 연결·테이블 문제로 실패하면 null 을 돌려주고 1분간 DB 를 건너뜁니다. */
    private <T> T db(Supplier<T> work) {
        if (System.currentTimeMillis() < dbRetryAt) {
            return null;
        }
        try {
            return work.get();
        } catch (RuntimeException e) {
            // 비밀정보가 섞일 수 있어 예외 메시지는 남기지 않습니다.
            LOG.warn("실적 DB 사용 불가, DART 직접 조회로 대체: {}", e.getClass().getSimpleName());
            dbRetryAt = System.currentTimeMillis() + 60_000L;
            return null;
        }
    }

    private void saveToDb(String stockCode, String corpCode, Fetched fetched) {
        if (System.currentTimeMillis() < dbRetryAt) {
            return;
        }
        String[] company = companyInfo(corpCode);
        if (company == null) {
            return;
        }
        Boolean ok = db(() -> {
            store.save(stockCode, company[0], company[1], corpCode, fetched.annual(), fetched.rows());
            return Boolean.TRUE;
        });
        if (ok != null) {
            LOG.info("실적: DB 저장 완료 {}", stockCode);
        }
    }

    /** [기업명, 시장구분]. 시장구분은 DART 기업개황의 corp_cls (Y 코스피, K 코스닥, N 코넥스). 실패하면 null. */
    private String[] companyInfo(String corpCode) {
        try {
            String response = client.get()
                    .uri(uri -> uri.path("/api/company.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode body = mapper.readTree(response);
            if (!"000".equals(body.path("status").asText(""))) {
                return null;
            }
            String name = body.path("corp_name").asText("");
            String market = switch (body.path("corp_cls").asText("")) {
                case "Y" -> "KOSPI";
                case "K" -> "KOSDAQ";
                case "N" -> "KONEX";
                default -> "ETC";
            };
            return name.isBlank() ? null : new String[] {name, market};
        } catch (Exception e) {
            LOG.warn("DART 기업개황 조회 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private Fetched fetch(String corpCode, int year) {
        JsonNode body;
        try {
            String response = client.get()
                    .uri(uri -> uri.path("/api/fnlttSinglAcnt.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", year)
                            .queryParam("reprt_code", ANNUAL_REPORT)
                            .build())
                    .retrieve()
                    .body(String.class);
            body = mapper.readTree(response);
        } catch (Exception e) {
            // 키가 담긴 요청 주소는 로그와 응답에 남기지 않습니다.
            LOG.warn("DART 통신 실패: {}", e.getClass().getSimpleName());
            throw new EarningsException(HttpStatus.BAD_GATEWAY, "DART 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }

        String status = body.path("status").asText("");
        if ("013".equals(status)) {
            return null; // 해당 사업연도 보고서 없음
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
        // 연결(CFS)이 하나라도 있으면 연결만, 없으면 별도(OFS)만 사용해 두 기준이 섞이지 않게 합니다.
        String fs = "OFS";
        for (JsonNode row : list) {
            if ("CFS".equals(row.path("fs_div").asText(""))) {
                fs = "CFS";
                break;
            }
        }

        List<Line> lines = new ArrayList<>();
        List<EarningsStore.ValueRow> rows = new ArrayList<>();
        boolean any = false;
        for (String[] account : ACCOUNTS) {
            JsonNode row = find(list, fs, account[1]);
            Long current = row == null ? null : EarningsMath.parse(row.path("thstrm_amount").asText(""));
            Long previous = row == null ? null : EarningsMath.parse(row.path("frmtrm_amount").asText(""));
            any |= current != null || previous != null;
            EarningsMath.Change change = EarningsMath.change(current, previous);
            lines.add(new Line(account[0], account[1], current, previous, change.percent(), change.status()));
            String currency = row == null ? "KRW" : row.path("currency").asText("KRW");
            rows.add(new EarningsStore.ValueRow(EarningsStore.accountCode(account[0]), account[1],
                    row == null ? null : row.path("account_nm").asText(null), current, previous,
                    currency.isBlank() ? "KRW" : currency));
        }
        if (!any) {
            return null;
        }

        String receiptNo = list.size() > 0 ? list.get(0).path("rcept_no").asText("") : "";
        Annual annual = new Annual(year, year - 1, "CFS".equals(fs) ? "연결" : "별도", "원",
                receiptNo, EarningsMath.dartUrl(receiptNo), lines);
        return new Fetched(annual, rows);
    }

    private static JsonNode find(JsonNode list, String fs, String account) {
        for (JsonNode row : list) {
            if (fs.equals(row.path("fs_div").asText("")) && row.path("account_nm").asText("").startsWith(account)) {
                return row;
            }
        }
        return null;
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
