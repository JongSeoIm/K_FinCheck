package com.example.stock_api.dart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.stream.XMLStreamException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.stock_api.stock.Stock;
import com.example.stock_api.stock.StockService;

import lombok.extern.slf4j.Slf4j;

/**
 * 챗봇이 찾을 국내 상장사 목록. 서버 메모리에 두고 하루에 한 번 OpenDART 에서 새로 받습니다.
 * company 테이블은 화면·실시간 표시 종목이므로 이 목록을 넣지 않습니다.
 * 받지 못하면 company 테이블의 종목만 조회할 수 있습니다.
 */
@Slf4j
@Service
public class ListedCompanies {

    private static final String MARKET = "국내 상장";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    private final RestClient rest;
    private final String apiKey;
    private final StockService stocks;

    private volatile Map<String, Stock> byCode = Map.of();
    private volatile Map<String, Stock> byName = Map.of();

    public ListedCompanies(@Value("${dart.base-url}") String baseUrl,
                           @Value("${dart.api-key}") String apiKey,
                           StockService stocks) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.stocks = stocks;
    }

    @Scheduled(fixedDelay = DAY_MS, initialDelay = 3_000)
    public void refresh() {
        if (apiKey == null || apiKey.isBlank()) return;
        try {
            byte[] file = rest.get()
                    .uri(u -> u.path("/api/corpCode.xml").queryParam("crtfc_key", apiKey).build())
                    .retrieve()
                    .body(byte[].class);
            if (file == null) throw new IOException("응답이 비어 있습니다.");
            apply(ListedCompanyParser.parse(new ByteArrayInputStream(file)));
        } catch (RestClientException | IOException | XMLStreamException e) {
            // 오류 문구에 주소가 들어가면 키가 함께 남으므로 종류만 기록합니다
            log.warn("상장사 목록을 받지 못했습니다: {}", e.getClass().getSimpleName());
        }
    }

    private void apply(List<ListedCompanyParser.Entry> entries) {
        Map<String, Stock> codes = new HashMap<>();
        Map<String, Stock> names = new HashMap<>();
        for (ListedCompanyParser.Entry entry : entries) {
            Stock stock = new Stock(entry.stockCode(), entry.name(), MARKET, null, entry.corpCode());
            codes.put(entry.stockCode(), stock);
            names.put(key(entry.name()), stock);
        }
        byCode = Map.copyOf(codes);
        byName = Map.copyOf(names);
        log.info("상장사 목록 {}개를 읽었습니다.", codes.size());
        fillDartCodes();
    }

    /** company 테이블에 고유번호가 비어 있는 종목을 채웁니다 */
    private void fillDartCodes() {
        for (Stock stock : stocks.allOrCached()) {
            Stock listed = byCode.get(stock.getCode());
            if (stock.getDartCorpCode() == null && listed != null) {
                stocks.fillDartCorpCode(stock.getCode(), listed.getDartCorpCode());
            }
        }
    }

    public Optional<Stock> findByCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public Optional<Stock> findByName(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    private static String key(String name) {
        return name.replaceAll("\\s+", "").toLowerCase();
    }
}
