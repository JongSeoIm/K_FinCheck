package com.kfincheck.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.client.dto.KisDailyChartResponse;
import com.kfincheck.client.dto.KisPriceResponse;
import com.kfincheck.client.dto.KisTokenRequest;
import com.kfincheck.client.dto.KisTokenResponse;
import com.kfincheck.config.KisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

@Component
public class KisApiClient {

    private static final Logger log = LoggerFactory.getLogger(KisApiClient.class);
    private static final String TOKEN_CACHE_FILE = ".kis_token.json";

    private final KisConfig kisConfig;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private String cachedAccessToken;
    private Instant tokenExpiryTime;

    public KisApiClient(KisConfig kisConfig) {
        this.kisConfig = kisConfig;
        this.objectMapper = new ObjectMapper();
        this.restClient = RestClient.builder()
                .baseUrl(kisConfig.getBaseUrl())
                .build();
        loadTokenFromFile();
    }

    public synchronized String getAccessToken() {
        if (isTokenValid()) {
            return cachedAccessToken;
        }

        loadTokenFromFile();
        if (isTokenValid()) {
            return cachedAccessToken;
        }

        validateKeyConfig();

        KisTokenRequest request = new KisTokenRequest(kisConfig.getAppKey(), kisConfig.getAppSecret());

        try {
            KisTokenResponse response = restClient.post()
                    .uri("/oauth2/tokenP")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(KisTokenResponse.class);

            if (response == null || response.getAccessToken() == null) {
                throw new IllegalStateException("KIS 접근토큰 응답이 비어 있습니다.");
            }

            this.cachedAccessToken = response.getAccessToken();
            long expiresInSeconds = (response.getExpiresIn() != null && response.getExpiresIn() > 0)
                    ? response.getExpiresIn()
                    : 86400L;
            this.tokenExpiryTime = Instant.now().plusSeconds(expiresInSeconds - 3600);

            saveTokenToFile(this.cachedAccessToken, this.tokenExpiryTime);
            log.info("KIS 접근토큰 발급 및 로컬 캐싱 완료 (만료 예정 시각: {})", tokenExpiryTime);
            return cachedAccessToken;
        } catch (Exception e) {
            log.error("KIS 접근토큰 발급 실패: {}", e.getMessage());
            throw new IllegalStateException("KIS 접근토큰 발급에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private static final Object CALL_LOCK = new Object();
    private static long lastCallEpochMs = 0L;
    private static final long MIN_INTERVAL_MS = 250L;

    private void paceRequest() {
        synchronized (CALL_LOCK) {
            long now = System.currentTimeMillis();
            long waitTime = MIN_INTERVAL_MS - (now - lastCallEpochMs);
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCallEpochMs = System.currentTimeMillis();
        }
    }

    public KisPriceResponse getStockPrice(String stockCode) {
        validateKeyConfig();
        String token = getAccessToken();

        for (int attempt = 1; attempt <= 2; attempt++) {
            paceRequest();
            try {
                return restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                                .queryParam("fid_cond_mrkt_div_code", "J")
                                .queryParam("fid_input_iscd", stockCode)
                                .build())
                        .header("authorization", "Bearer " + token)
                        .header("appkey", kisConfig.getAppKey())
                        .header("appsecret", kisConfig.getAppSecret())
                        .header("tr_id", "FHKST01010100")
                        .header("custtype", "P")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(KisPriceResponse.class);
            } catch (Exception e) {
                if (attempt == 1 && e.getMessage() != null && e.getMessage().contains("EGW00201")) {
                    log.warn("KIS 현재가 초당 거래건수 초과(EGW00201) 감지, 600ms 후 자동 재시도합니다. (종목코드: {})", stockCode);
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                log.error("KIS 현재가 조회 통신 실패 (종목코드: {}): {}", stockCode, e.getMessage());
                throw new IllegalStateException("KIS 현재가 조회 통신에 실패했습니다: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("KIS 현재가 조회 재시도 초과");
    }

    public KisDailyChartResponse getDailyCandles(String stockCode, String startDate, String endDate) {
        return getDailyCandles(stockCode, startDate, endDate, "D");
    }

    public KisDailyChartResponse getDailyCandles(String stockCode, String startDate, String endDate, String periodType) {
        validateKeyConfig();
        String token = getAccessToken();
        String periodCode = (periodType != null && !periodType.isBlank()) ? periodType.trim().toUpperCase() : "D";

        for (int attempt = 1; attempt <= 2; attempt++) {
            paceRequest();
            try {
                return restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", stockCode)
                                .queryParam("FID_INPUT_DATE_1", startDate)
                                .queryParam("FID_INPUT_DATE_2", endDate)
                                .queryParam("FID_PERIOD_DIV_CODE", periodCode)
                                .queryParam("FID_ORG_ADJ_PRC", "0")
                                .build())
                        .header("authorization", "Bearer " + token)
                        .header("appkey", kisConfig.getAppKey())
                        .header("appsecret", kisConfig.getAppSecret())
                        .header("tr_id", "FHKST03010100")
                        .header("custtype", "P")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(KisDailyChartResponse.class);
            } catch (Exception e) {
                if (attempt == 1 && e.getMessage() != null && e.getMessage().contains("EGW00201")) {
                    log.warn("KIS 기간별 시세 초당 거래건수 초과(EGW00201) 감지, 600ms 후 자동 재시도합니다. (종목코드: {}, 주기: {})", stockCode, periodCode);
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                log.error("KIS 기간별 시세 조회 통신 실패 (종목코드: {}, 주기: {}): {}", stockCode, periodCode, e.getMessage());
                throw new IllegalStateException("KIS 기간별 시세 조회 통신에 실패했습니다: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("KIS 기간별 시세 조회 재시도 초과");
    }

    private boolean isTokenValid() {
        return cachedAccessToken != null
                && tokenExpiryTime != null
                && Instant.now().isBefore(tokenExpiryTime);
    }

    private void loadTokenFromFile() {
        File file = new File(TOKEN_CACHE_FILE);
        if (!file.exists()) return;

        try {
            String content = Files.readString(file.toPath());
            Map<?, ?> map = objectMapper.readValue(content, Map.class);
            String token = (String) map.get("token");
            Number expirySec = (Number) map.get("expiryEpochSec");

            if (token != null && expirySec != null) {
                Instant expiry = Instant.ofEpochSecond(expirySec.longValue());
                if (Instant.now().isBefore(expiry)) {
                    this.cachedAccessToken = token;
                    this.tokenExpiryTime = expiry;
                    log.info("로컬 캐시 파일에서 KIS 토큰 복원 완료 (만료 시각: {})", expiry);
                }
            }
        } catch (Exception e) {
            log.warn("로컬 토큰 캐시 파일 읽기 실패 (무시하고 새로 발급): {}", e.getMessage());
        }
    }

    private void saveTokenToFile(String token, Instant expiry) {
        try {
            Map<String, Object> map = Map.of(
                    "token", token,
                    "expiryEpochSec", expiry.getEpochSecond()
            );
            Files.writeString(new File(TOKEN_CACHE_FILE).toPath(), objectMapper.writeValueAsString(map));
        } catch (Exception e) {
            log.warn("로컬 토큰 캐시 파일 저장 실패 (메모리 캐시는 유지됨): {}", e.getMessage());
        }
    }

    private void validateKeyConfig() {
        if (kisConfig.getAppKey() == null || kisConfig.getAppKey().isBlank()
                || "YOUR_APP_KEY".equals(kisConfig.getAppKey())
                || "YOUR_KIS_APP_KEY_HERE".equals(kisConfig.getAppKey())) {
            throw new IllegalStateException("KIS App Key가 설정되지 않았습니다. application-local.yml 설정을 확인하세요.");
        }
        if (kisConfig.getAppSecret() == null || kisConfig.getAppSecret().isBlank()
                || "YOUR_APP_SECRET".equals(kisConfig.getAppSecret())
                || "YOUR_KIS_APP_SECRET_HERE".equals(kisConfig.getAppSecret())) {
            throw new IllegalStateException("KIS App Secret이 설정되지 않았습니다. application-local.yml 설정을 확인하세요.");
        }
    }
}
