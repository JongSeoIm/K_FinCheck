package com.kfincheck.client;

import com.kfincheck.client.dto.KisDailyChartResponse;
import com.kfincheck.client.dto.KisPriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KisApiClientIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(KisApiClientIntegrationTest.class);

    @Autowired
    private KisApiClient kisApiClient;

    @BeforeEach
    void waitBetweenRequests() throws InterruptedException {
        Thread.sleep(1100);
    }

    @Test
    @DisplayName("삼성전자(005930) 현재가 실제 KIS API 조회 검증")
    void getSamsungStockPriceRealApi() {
        KisPriceResponse response = kisApiClient.getStockPrice("005930");

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).isNotNull();

        KisPriceResponse.PriceOutput output = response.getOutput();
        log.info("삼성전자 현재가 조회 성공 - 현재가: {}, 전일대비: {}, 등락률: {}%, 누적거래량: {}",
                output.getStckPrpr(), output.getPrdyVrss(), output.getPrdyCtrt(), output.getAcmlVol());

        assertThat(output.getStckPrpr()).isNotBlank();
        assertThat(Long.parseLong(output.getStckPrpr())).isGreaterThan(0L);
    }

    @Test
    @DisplayName("SK하이닉스(000660) 현재가 실제 KIS API 두 번째 종목 조회 검증")
    void getHynixStockPriceRealApi() {
        KisPriceResponse response = kisApiClient.getStockPrice("000660");

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).isNotNull();

        KisPriceResponse.PriceOutput output = response.getOutput();
        log.info("SK하이닉스 현재가 조회 성공 - 현재가: {}, 전일대비: {}, 등락률: {}%, 누적거래량: {}",
                output.getStckPrpr(), output.getPrdyVrss(), output.getPrdyCtrt(), output.getAcmlVol());

        assertThat(output.getStckPrpr()).isNotBlank();
        assertThat(Long.parseLong(output.getStckPrpr())).isGreaterThan(0L);
    }

    @Test
    @DisplayName("삼성전자(005930) 과거 일봉 OHLC 실제 KIS API 조회 검증")
    void getSamsungDailyCandlesRealApi() {
        String endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDate = LocalDate.now().minusDays(45).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        KisDailyChartResponse response = kisApiClient.getDailyCandles("005930", startDate, endDate);

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput2()).isNotNull();
        assertThat(response.getOutput2()).isNotEmpty();

        KisDailyChartResponse.DailyCandleOutput latest = response.getOutput2().get(0);
        log.info("삼성전자 일봉 수신 성공 - 수신건수: {}, 최근일: {}, 시가: {}, 고가: {}, 저가: {}, 종가: {}, 거래량: {}",
                response.getOutput2().size(), latest.getStckBsopDate(), latest.getStckOprc(),
                latest.getStckHgpr(), latest.getStckLwpr(), latest.getStckClpr(), latest.getAcmlVol());

        assertThat(latest.getStckClpr()).isNotBlank();
        assertThat(Long.parseLong(latest.getStckClpr())).isGreaterThan(0L);
    }
}
