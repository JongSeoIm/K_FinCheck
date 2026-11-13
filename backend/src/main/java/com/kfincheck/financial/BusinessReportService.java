package com.kfincheck.financial;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 사업보고서 RAG 용 원문 제공.
 * ① 종목코드 → OpenDART 고유번호(corp_code): company 테이블 값, 없으면 corpCode.xml 에서 찾아 DB 에 채운다.
 * ② 최근 18개월 안의 최신 사업보고서(A001) 접수번호를 찾는다.
 * ③ 원문 ZIP(document.xml)을 그대로 AI 서버에 넘긴다. 문서 파싱·청크·벡터화는 AI 서버가 한다.
 * OpenDART 호출을 줄이려고 보고서 정보와 원문은 6시간, 고유번호 목록은 하루 동안 메모리에 둔다.
 */
@Service
public class BusinessReportService {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration CACHE = Duration.ofHours(6);
    private static final Pattern YEAR = Pattern.compile("\\((\\d{4})\\.\\d{2}\\)");
    private static final Pattern LIST = Pattern.compile("<list>(.*?)</list>", Pattern.DOTALL);

    public record Report(String stockCode, String companyName, String corpCode, String receiptNo, String reportName,
                         Integer businessYear, LocalDate receivedDate, String sourceUrl) { }

    private record Cached<T>(T value, Instant at) {
        boolean fresh() { return at.plus(CACHE).isAfter(Instant.now()); }
    }

    private final OpenDartClient dart;
    private final JdbcTemplate jdbc;
    private final Map<String, Cached<Report>> reports = new ConcurrentHashMap<>();
    private final Map<String, Cached<byte[]>> documents = new ConcurrentHashMap<>();
    private final Map<String, String> corpCodes = new ConcurrentHashMap<>();
    private volatile Instant corpCodesLoadedAt = Instant.EPOCH;

    public BusinessReportService(OpenDartClient dart, JdbcTemplate jdbc) {
        this.dart = dart;
        this.jdbc = jdbc;
    }

    public Report latest(String stockCode) {
        return find(stockCode, null);
    }

    /**
     * year 가 없으면 최근 18개월 안의 최신 사업보고서.
     * year 가 있으면 그 사업연도 보고서. Y년 사업보고서는 보통 Y+1년 3월에 공시되므로 Y+1년 1~12월 접수분에서 찾는다.
     */
    public Report find(String stockCode, Integer year) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new FinancialException(HttpStatus.BAD_REQUEST, "INVALID_STOCK", "종목 코드는 숫자 6자리여야 합니다.");
        }
        LocalDate today = LocalDate.now(KST);
        if (year != null && (year < 2015 || year >= today.getYear())) {
            throw new FinancialException(HttpStatus.BAD_REQUEST, "INVALID_YEAR", "사업보고서는 2015년부터 전년도까지 조회할 수 있습니다.");
        }
        String key = stockCode + ":" + (year == null ? "latest" : year);
        Cached<Report> cached = reports.get(key);
        if (cached != null && cached.fresh()) return cached.value();
        String name = companyName(stockCode);
        String corp = corpCode(stockCode);
        LocalDate start = year == null ? today.minusMonths(18) : LocalDate.of(year + 1, 1, 1);
        LocalDate end = year == null ? today : LocalDate.of(year + 1, 12, 31);
        if (end.isAfter(today)) end = today;
        JsonNode list = dart.annualReportFilings(corp,
                start.format(DateTimeFormatter.BASIC_ISO_DATE), end.format(DateTimeFormatter.BASIC_ISO_DATE));
        for (JsonNode row : list) {
            String title = row.path("report_nm").asText().strip();
            String receipt = row.path("rcept_no").asText();
            if (!title.contains("사업보고서") || !receipt.matches("[0-9]{14}")) continue;
            Matcher titleYear = YEAR.matcher(title);
            Integer businessYear = titleYear.find() ? Integer.valueOf(titleYear.group(1)) : null;
            // 연도를 지정했으면 제목의 사업연도가 같은 보고서만 쓴다. 다른 연도 보고서로 답하지 않기 위해서다.
            if (year != null && !year.equals(businessYear)) continue;
            LocalDate received = null;
            try {
                received = LocalDate.parse(row.path("rcept_dt").asText(), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (RuntimeException ignored) { /* 접수일이 없으면 비워 둔다. */ }
            Report report = new Report(stockCode, name, corp, receipt, title, businessYear, received,
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt);
            reports.put(key, new Cached<>(report, Instant.now()));
            return report;
        }
        throw new FinancialException(HttpStatus.NOT_FOUND, "NO_REPORT",
                year == null ? "최근 사업보고서를 찾지 못했습니다." : year + "년 사업보고서를 찾지 못했습니다.");
    }

    public byte[] document(String receiptNo) {
        Cached<byte[]> cached = documents.get(receiptNo);
        if (cached != null && cached.fresh()) return cached.value();
        byte[] zip = dart.documentZip(receiptNo);
        documents.put(receiptNo, new Cached<>(zip, Instant.now()));
        return zip;
    }

    private String companyName(String stockCode) {
        List<String> names;
        try {
            names = jdbc.queryForList("SELECT company_name FROM company WHERE stock_code = ?", String.class, stockCode);
        } catch (DataAccessException error) {
            throw new FinancialException(HttpStatus.SERVICE_UNAVAILABLE, "FINANCIAL_DB_UNAVAILABLE", "기업 정보를 조회하지 못했습니다. DB 설정을 확인해 주세요.");
        }
        if (names.isEmpty()) {
            throw new FinancialException(HttpStatus.NOT_FOUND, "UNSUPPORTED_STOCK", "사업보고서는 화면의 지원 종목만 조회할 수 있습니다.");
        }
        return names.getFirst();
    }

    /** 종목 코드 → OpenDART 고유번호. 공시 목록 저장(DisclosureService)도 같이 쓴다. */
    String corpCode(String stockCode) {
        try {
            List<String> saved = jdbc.queryForList("SELECT dart_corp_code FROM company WHERE stock_code = ?", String.class, stockCode);
            if (!saved.isEmpty() && saved.getFirst() != null && saved.getFirst().matches("[0-9]{8}")) return saved.getFirst();
        } catch (DataAccessException ignored) { /* 아래 OpenDART 목록으로 찾는다. */ }
        if (corpCodes.isEmpty() || corpCodesLoadedAt.plus(Duration.ofDays(1)).isBefore(Instant.now())) loadCorpCodes();
        String code = corpCodes.get(stockCode);
        if (code == null) throw new FinancialException(HttpStatus.NOT_FOUND, "NO_CORP_CODE", "OpenDART 기업 고유번호를 찾지 못했습니다.");
        try {
            jdbc.update("UPDATE company SET dart_corp_code = ? WHERE stock_code = ? AND dart_corp_code IS NULL", code, stockCode);
        } catch (DataAccessException ignored) { /* 저장 실패해도 조회는 계속한다. */ }
        return code;
    }

    private synchronized void loadCorpCodes() {
        if (!corpCodes.isEmpty() && corpCodesLoadedAt.plus(Duration.ofDays(1)).isAfter(Instant.now())) return;
        Map<String, String> parsed = parseCorpCodes(dart.corpCodeZip());
        corpCodes.clear();
        corpCodes.putAll(parsed);
        corpCodesLoadedAt = Instant.now();
    }

    /** corpCode.xml ZIP → {종목코드: 고유번호}. 상장사(stock_code 6자리)만 담는다. */
    static Map<String, String> parseCorpCodes(byte[] zip) {
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) continue;
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> result = new HashMap<>();
                Matcher block = LIST.matcher(xml);
                while (block.find()) {
                    String corp = tag(block.group(1), "corp_code");
                    String stock = tag(block.group(1), "stock_code");
                    if (corp.matches("\\d{8}") && stock.matches("\\d{6}")) result.put(stock, corp);
                }
                return result;
            }
        } catch (IOException error) {
            throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_INVALID_FILE", "기업 고유번호 파일을 읽지 못했습니다.");
        }
        throw new FinancialException(HttpStatus.BAD_GATEWAY, "DART_INVALID_FILE", "기업 고유번호 파일이 비어 있습니다.");
    }

    private static String tag(String block, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }
}
