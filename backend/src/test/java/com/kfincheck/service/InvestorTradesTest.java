package com.kfincheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kfincheck.client.KisApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestorTradesTest {

    @Test
    void rankingUsesLatestDateSharedByAllEightStocksAndReusesDailyResponses() throws Exception {
        var client = mock(KisApiClient.class);
        var mapper = new ObjectMapper();
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var date = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        var previous = today.minusDays(1);
        var previousDate = previous.format(DateTimeFormatter.BASIC_ISO_DATE);
        var complete = """
                {"stck_bsop_date":"%s","prsn_ntby_qty":"50",
                 "prsn_ntby_tr_pbmn":"135","orgn_ntby_tr_pbmn":"0","frgn_ntby_tr_pbmn":"-135"}
                """;
        when(client.getInvestorTrades(anyString(), eq(date), eq("")))
                .thenReturn(ResponseEntity.ok(mapper.readTree("""
                        {"rt_cd":"0","output2":[%s,%s]}
                        """.formatted(complete.formatted(date), complete.formatted(previousDate)))));
        when(client.getInvestorTrades("005930", date, ""))
                .thenReturn(ResponseEntity.ok(mapper.readTree("""
                        {"rt_cd":"0","output2":[{"stck_bsop_date":"%s","prsn_ntby_tr_pbmn":""},%s]}
                        """.formatted(date, complete.formatted(previousDate)))));

        var service = new StockService(client);
        var ranking = service.getInvestorRanking();
        assertThat(ranking.date()).isEqualTo(previous);
        assertThat(ranking.stocks()).hasSize(8)
                .contains(new StockService.InvestorAmounts("005930", 135L, 0L, -135L));
        assertThat(service.getInvestorTrades("005930")).containsExactly(
                new StockService.InvestorTrade(previous, 50L, null, null));
        verify(client).getInvestorTrades("005930", date, "");
    }

    @Test
    void reusesThePreviousDateAcrossTheRankingWhenKisRejectsTheCurrentDate() throws Exception {
        var client = mock(KisApiClient.class);
        var mapper = new ObjectMapper();
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var previous = today.minusDays(1);
        var formatter = DateTimeFormatter.BASIC_ISO_DATE;
        when(client.getInvestorTrades(anyString(), eq(today.format(formatter)), eq("")))
                .thenReturn(ResponseEntity.ok(mapper.readTree("""
                        {"rt_cd":"2","msg_cd":"OPSQ2001"}
                        """)));
        when(client.getInvestorTrades(anyString(), eq(previous.format(formatter)), eq("")))
                .thenReturn(ResponseEntity.ok(mapper.readTree("""
                        {"rt_cd":"0","output2":[
                          {"stck_bsop_date":"%s","prsn_ntby_qty":"99","prsn_ntby_tr_pbmn":"3","orgn_ntby_tr_pbmn":"0","frgn_ntby_tr_pbmn":"-3"},
                          {"stck_bsop_date":"%s","prsn_ntby_qty":"5","prsn_ntby_tr_pbmn":"3","orgn_ntby_tr_pbmn":"0","frgn_ntby_tr_pbmn":"-3"}
                        ]}
                        """.formatted(today.format(formatter), previous.format(formatter)))));

        var service = new StockService(client);
        var ranking = service.getInvestorRanking();
        assertThat(ranking.date()).isEqualTo(previous);
        assertThat(ranking.stocks()).hasSize(8);
        assertThat(service.getInvestorTrades("005930"))
                .containsExactly(new StockService.InvestorTrade(previous, 5L, null, null));
        assertThat(service.getInvestorRanking()).isEqualTo(ranking);
        verify(client).getInvestorTrades(anyString(), eq(today.format(formatter)), eq(""));
        verify(client, times(8)).getInvestorTrades(anyString(), eq(previous.format(formatter)), eq(""));
    }

    @Test
    void latestFiveTradingDaysAreSortedWithoutTreatingMissingQuantitiesAsZero() throws Exception {
        var client = mock(KisApiClient.class);
        var mapper = new ObjectMapper();
        when(client.getInvestorTrades(eq("005930"), anyString(), eq("")))
                .thenReturn(ResponseEntity.ok().header("tr_cont", "M").body(mapper.readTree("""
                        {"rt_cd":"0","output2":[
                          {"stck_bsop_date":"20261110","prsn_ntby_qty":"-120","orgn_ntby_qty":"0","frgn_ntby_qty":""},
                          {"stck_bsop_date":"20261109","prsn_ntby_qty":"25"},
                          {"stck_bsop_date":"20261111","prsn_ntby_qty":""}
                        ]}
                        """)));
        when(client.getInvestorTrades(eq("005930"), anyString(), eq("N")))
                .thenReturn(ResponseEntity.ok(mapper.readTree("""
                        {"rt_cd":"0","output2":[
                          {"stck_bsop_date":"20261106","frgn_ntby_qty":"80"},
                          {"stck_bsop_date":"20261105","frgn_ntby_qty":"90"},
                          {"stck_bsop_date":"20261104","frgn_ntby_qty":"100"},
                          {"stck_bsop_date":"20261103","frgn_ntby_qty":"75"}
                        ]}
                        """)));

        var service = new StockService(client);
        var rows = service.getInvestorTrades("005930");

        assertThat(rows).extracting(StockService.InvestorTrade::date).containsExactlyElementsOf(List.of(
                LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10)));
        assertThat(rows.getLast()).isEqualTo(new StockService.InvestorTrade(LocalDate.of(2026, 11, 10), -120L, 0L, null));
        assertThat(service.getInvestorTrades("005930")).isEqualTo(rows);
        verify(client).getInvestorTrades(eq("005930"), anyString(), eq(""));
        verify(client).getInvestorTrades(eq("005930"), anyString(), eq("N"));
    }
}
