package com.kfincheck.opinion;

import com.kfincheck.client.KisApiClient;
import com.kfincheck.config.KisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpinionServiceTest {
    MockRestServiceServer server;
    OpinionService service;
    KisApiClient authentication;
    String today;

    @BeforeEach
    void setup() {
        var config = new KisConfig();
        config.setBaseUrl("https://example.test");
        config.setAppKey("test-key"); config.setAppSecret("test-secret");
        authentication = mock(KisApiClient.class);
        when(authentication.getAccessToken()).thenReturn("test-token");
        var builder = RestClient.builder().baseUrl(config.getBaseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        service = new OpinionService(config, authentication, builder.build());
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    @Test
    void mapsBrokerTargetAndDateAndCachesResult() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://example.test/uapi/domestic-stock/v1/quotations/invest-opinion?")))
                .andExpect(header("tr_id", "FHKST663300C0"))
                .andExpect(queryParam("FID_INPUT_ISCD", "005930"))
                .andExpect(queryParam("FID_INPUT_DATE_1", LocalDate.now(ZoneId.of("Asia/Seoul")).minusMonths(3).format(DateTimeFormatter.BASIC_ISO_DATE)))
                .andRespond(withSuccess("""
                  {"rt_cd":"0","output":[{"stck_bsop_date":"%s","mbcr_name":"테스트증권","invt_opnn":"매수","hts_goal_prc":"100,000"}]}
                  """.formatted(today), MediaType.APPLICATION_JSON));
        var result = service.latest("005930");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().broker()).isEqualTo("테스트증권");
        assertThat(result.items().getFirst().targetPrice()).isEqualByComparingTo("100000");
        assertThat(service.latest("005930")).isSameAs(result);
        server.verify();
    }

    @Test
    void emptySuccessIsNotTransportFailure() {
        server.expect(anything()).andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[]}", MediaType.APPLICATION_JSON));
        var result = service.latest("005930");
        assertThat(result.items()).isEmpty();
        assertThat(result.distribution()).allMatch(item -> item.percentage() == null && item.count() == 0);
        server.verify();
    }

    @Test
    void missingTargetIsNotZeroAndInvalidDatesAreExcluded() {
        server.expect(anything()).andRespond(withSuccess("""
          {"rt_cd":"0","output":[
            {"stck_bsop_date":"%s","mbcr_name":"테스트증권","invt_opnn":"중립","hts_goal_prc":"0"},
            {"stck_bsop_date":"invalid","invt_opnn":"매수","hts_goal_prc":"100"},
            {"stck_bsop_date":"20000101","invt_opnn":"매수","hts_goal_prc":"100"}]}
          """.formatted(today), MediaType.APPLICATION_JSON));
        var items = service.latest("005930").items();
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().targetPrice()).isNull();
        server.verify();
    }

    @Test
    void providerErrorDoesNotLeakResponseOrBecomeEmptySuccess() {
        server.expect(anything()).andRespond(withSuccess("{\"rt_cd\":\"1\",\"msg1\":\"private provider detail\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.latest("005930"))
                .isInstanceOf(OpinionService.OpinionException.class)
                .hasMessageNotContaining("private provider detail");
        server.verify();
    }

    @Test
    void httpErrorHasSafeServiceUnavailableStatus() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        try { service.latest("005930"); fail("Expected provider failure"); }
        catch (OpinionService.OpinionException error) { assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); }
        server.verify();
    }

    @Test
    void sortsAllBrokersWithoutFiveRowLimitAndNormalizesKnownLabels() {
        var output = new java.util.ArrayList<String>();
        LocalDate end = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int day = 6; day >= 0; day--) {
            output.add("""
              {"stck_bsop_date":"%s","mbcr_name":"증권사%d","invt_opnn":"BUY","hts_goal_prc":"100000"}
              """.formatted(end.minusDays(day).format(DateTimeFormatter.BASIC_ISO_DATE), day));
        }
        server.expect(anything()).andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[" + String.join(",", output) + "]}", MediaType.APPLICATION_JSON));
        var items = service.latest("005930").items();
        assertThat(items).hasSize(7);
        assertThat(items.getFirst().date()).isEqualTo(end);
        assertThat(items.getLast().date()).isEqualTo(end.minusDays(6));
        assertThat(items).allMatch(item -> item.opinion().equals("매수"));
        server.verify();
    }

    @Test
    void countsLatestPerBrokerAndExcludesUnclassifiedWithoutUsingOlderOpinion() {
        String yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        server.expect(anything()).andRespond(withSuccess("""
          {"rt_cd":"0","output":[
            {"stck_bsop_date":"%1$s","mbcr_name":"A","invt_opnn":"BUY"},
            {"stck_bsop_date":"%2$s","mbcr_name":"A","invt_opnn":"SELL"},
            {"stck_bsop_date":"%1$s","mbcr_name":"B","invt_opnn":"HOLD"},
            {"stck_bsop_date":"%1$s","mbcr_name":"C","invt_opnn":"SELL"},
            {"stck_bsop_date":"%1$s","mbcr_name":"D","invt_opnn":"Not Rated"},
            {"stck_bsop_date":"%2$s","mbcr_name":"D","invt_opnn":"BUY"},
            {"stck_bsop_date":"%1$s","invt_opnn":"BUY"}]}
          """.formatted(today, yesterday), MediaType.APPLICATION_JSON));
        var result = service.latest("005930");
        assertThat(result.countedBrokers()).isEqualTo(3);
        assertThat(result.excludedBrokers()).isEqualTo(1);
        assertThat(result.missingBrokerRows()).isEqualTo(1);
        assertThat(result.items()).hasSize(4);
        assertThat(result.distribution()).allMatch(item -> item.count() == 1 && item.percentage().doubleValue() == 33.3);
        server.verify();
    }

    @Test
    void ambiguousSameDayOpinionsDoNotBecomeArbitraryVotes() {
        server.expect(anything()).andRespond(withSuccess("""
          {"rt_cd":"0","output":[
            {"stck_bsop_date":"%1$s","mbcr_name":"A","invt_opnn":"BUY"},
            {"stck_bsop_date":"%1$s","mbcr_name":"A","invt_opnn":"SELL"}]}
          """.formatted(today), MediaType.APPLICATION_JSON));
        var result = service.latest("005930");
        assertThat(result.countedBrokers()).isZero();
        assertThat(result.excludedBrokers()).isEqualTo(1);
        assertThat(result.distribution()).allMatch(item -> item.percentage() == null);
        server.verify();
    }

    @Test
    void flagsIncompleteProviderResponse() {
        server.expect(anything()).andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[]}", MediaType.APPLICATION_JSON)
                .header("tr_cont", "M"));
        assertThat(service.latest("005930").partial()).isTrue();
        server.verify();
    }

    @Test
    void invalidStockNeverCallsProvider() {
        assertThatThrownBy(() -> service.latest("bad")).isInstanceOf(OpinionService.OpinionException.class);
        verifyNoInteractions(authentication);
    }
}
