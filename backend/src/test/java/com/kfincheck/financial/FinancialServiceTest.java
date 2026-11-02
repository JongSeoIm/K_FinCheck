package com.kfincheck.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsStandardIncomeAccountsAndCalculatesGrowth() throws Exception {
        var rows = mapper.readTree("""
            [
              {"sj_div":"BS","account_id":"ifrs-full_Revenue","thstrm_amount":"999","currency":"KRW"},
              {"sj_div":"IS","account_id":"ifrs-full_Revenue","thstrm_amount":"1,200","frmtrm_amount":"1000","currency":"KRW","rcept_no":"20260301000001"},
              {"sj_div":"IS","account_id":"dart_OperatingIncomeLoss","thstrm_amount":"200","frmtrm_amount":"-50","currency":"KRW"},
              {"sj_div":"IS","account_id":"ifrs-full_ProfitLossAttributableToOwnersOfParent","thstrm_amount":"999","currency":"KRW"},
              {"sj_div":"CIS","account_id":"ifrs-full_ProfitLoss","thstrm_amount":"100","frmtrm_amount":"80","currency":"KRW"}
            ]
            """);
        var report = FinancialService.normalize("005930", "00126380", 2025, rows);
        assertThat(report.metrics().get(0).currentAmount()).isEqualTo("1200");
        assertThat(report.metrics().get(0).growthRate()).isEqualByComparingTo("20");
        assertThat(report.metrics().get(1).comparison()).isEqualTo("TURNAROUND");
        assertThat(report.metrics().get(1).growthRate()).isNull();
        assertThat(report.metrics().get(2).currentAmount()).isEqualTo("100");
        assertThat(report.sourceUrl()).endsWith("rcpNo=20260301000001");
    }

    @Test
    void missingAmbiguousAndForeignCurrencyAmountsAreNotZero() throws Exception {
        var rows = mapper.readTree("""
            [
              {"sj_div":"IS","account_id":"ifrs-full_Revenue","thstrm_amount":"100","currency":"KRW"},
              {"sj_div":"IS","account_id":"ifrs-full_Revenue","thstrm_amount":"200","currency":"KRW"},
              {"sj_div":"IS","account_id":"dart_OperatingIncomeLoss","thstrm_amount":"-","currency":"KRW"},
              {"sj_div":"IS","account_id":"ifrs-full_ProfitLoss","thstrm_amount":"10","currency":"USD"}
            ]
            """);
        var report = FinancialService.normalize("005930", "00126380", 2025, rows);
        assertThat(report.metrics()).extracting(FinancialReport.Metric::status)
                .containsExactly("AMBIGUOUS", "MISSING", "UNSUPPORTED_CURRENCY");
        assertThat(report.metrics()).allMatch(metric -> metric.currentAmount() == null);
    }

    @Test
    void checksScopeCachesSuccessfulReportsAndDoesNotCacheFailures() throws Exception {
        OpenDartClient client = mock(OpenDartClient.class);
        FinancialService service = new FinancialService(client);
        int year = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear() - 1;
        when(client.annualReport("00126380", year)).thenReturn(mapper.readTree("[]"));
        assertThat(service.annual("005930", null)).isSameAs(service.annual("005930", year));
        verify(client, times(1)).annualReport("00126380", year);
        assertThatThrownBy(() -> service.annual("035420", year)).isInstanceOf(FinancialException.class);
        assertThatThrownBy(() -> service.annual("005930", year + 1)).isInstanceOf(FinancialException.class);
        when(client.annualReport("00164779", year)).thenThrow(new FinancialException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "DART_UNAVAILABLE", "일시 오류"));
        assertThatThrownBy(() -> service.annual("000660", year)).isInstanceOf(FinancialException.class);
        assertThatThrownBy(() -> service.annual("000660", year)).isInstanceOf(FinancialException.class);
        verify(client, times(2)).annualReport("00164779", year);
    }

    @Test
    void usesKnownNameFallbackAndDoesNotDivideByZero() throws Exception {
        var report = FinancialService.normalize("005930", "00126380", 2025, mapper.readTree("""
            [{"sj_div":"IS","account_id":"-표준계정코드 미사용-","account_nm":"영업이익(손실)",
              "thstrm_amount":"0","frmtrm_amount":"0","currency":"KRW"}]
            """));
        assertThat(report.metrics().get(1).currentAmount()).isEqualTo("0");
        assertThat(report.metrics().get(1).growthRate()).isNull();
        assertThat(report.metrics().get(0).status()).isEqualTo("MISSING");
    }

    @Test
    void missingKeyIsSafeAndDoesNotMakeNetworkRequest() {
        var client = new OpenDartClient(new OpenDartConfig(), org.springframework.web.client.RestClient.builder());
        assertThatThrownBy(() -> client.annualReport("00126380", 2025))
                .isInstanceOf(FinancialException.class).hasMessage("OpenDART 인증키 설정이 필요합니다.");
    }
}
