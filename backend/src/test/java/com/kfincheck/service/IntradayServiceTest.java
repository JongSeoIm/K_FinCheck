package com.kfincheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class IntradayServiceTest {
    @Test void usesEightDigitKoreanDateAndPagesBackwardThenCaches() {
        var config=new com.kfincheck.config.KisConfig();config.setBaseUrl("https://example.test");config.setAppKey("test-key");config.setAppSecret("test-secret");
        var auth=org.mockito.Mockito.mock(com.kfincheck.client.KisApiClient.class);
        org.mockito.Mockito.when(auth.getAccessToken()).thenReturn("test-token");
        var builder=org.springframework.web.client.RestClient.builder().baseUrl(config.getBaseUrl());
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var service=new IntradayService(auth,config,builder.build());
        var day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);
        var formatted=day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        for(int minute:java.util.List.of(3,2,1)) server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("FID_INPUT_DATE_1",formatted))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("FID_INPUT_HOUR_1",org.hamcrest.Matchers.matchesPattern("\\d{4}00")))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("tr_id","FHKST03010230"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
              {"rt_cd":"0","output2":[{"stck_bsop_date":"%s","stck_cntg_hour":"090%d00","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"105","cntg_vol":"10"}]}
              """.formatted(formatted,minute),org.springframework.http.MediaType.APPLICATION_JSON));
        var before=day.atTime(15,30).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toString();
        var result=service.history("005930",before);
        assertThat(result.candles()).hasSize(3);
        assertThat(result.hasMore()).isTrue();
        assertThat(Instant.parse(result.nextBefore())).isBefore(Instant.parse(result.candles().getFirst().date()));
        assertThat(service.history("005930",before)).isSameAs(result);
        server.verify();
    }
    @Test void parsesSortedClosedMinutesAndRemovesDuplicatesInvalidRowsAndFutureMinutes() throws Exception {
        var rows=new ObjectMapper().readTree("""
          [{"stck_bsop_date":"20261104","stck_cntg_hour":"090200","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"105","cntg_vol":"10"},
           {"stck_bsop_date":"20261104","stck_cntg_hour":"090100","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"104","cntg_vol":"20"},
           {"stck_bsop_date":"20261104","stck_cntg_hour":"090100","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"104","cntg_vol":"20"},
           {"stck_bsop_date":"20261104","stck_cntg_hour":"090300","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"105","cntg_vol":"10"},
           {"stck_bsop_date":"20261104","stck_cntg_hour":"090000","stck_oprc":"0","stck_hgpr":"0","stck_lwpr":"0","stck_prpr":"0","cntg_vol":"0"}]
          """);
        var result=IntradayService.parse(rows,Instant.parse("2026-11-04T00:02:59Z"),Instant.parse("2025-11-04T00:00:00Z"));
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().date()).isEqualTo("2026-11-04T00:01:00Z");
        assertThat(result.getFirst().volume()).isEqualTo(20);
        assertThat(result.getLast().close()).isEqualTo(105);
    }
    @Test void refusesRowsOutsideTheRetentionWindow() throws Exception {
        var rows=new ObjectMapper().readTree("""
          [{"stck_bsop_date":"20240101","stck_cntg_hour":"090100","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"105","cntg_vol":"10"}]
          """);
        assertThat(IntradayService.parse(rows,Instant.parse("2026-11-04T00:02:00Z"),Instant.parse("2025-11-04T00:00:00Z"))).isEmpty();
    }
}
