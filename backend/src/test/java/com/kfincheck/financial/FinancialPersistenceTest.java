package com.kfincheck.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class FinancialPersistenceTest {
    @Autowired FinancialService service;
    @Autowired FinancialRepository repository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OpenDartClient client;
    private final ObjectMapper json = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode rows() throws Exception {
        return json.readTree("""
          [
            {"rcept_no":"99990101000001","sj_div":"IS","account_id":"ifrs-full_Revenue","account_nm":"수익(매출액)","thstrm_amount":"120000000000001","frmtrm_amount":"100000000000000","currency":"KRW"},
            {"rcept_no":"99990101000001","sj_div":"IS","account_id":"dart_OperatingIncomeLoss","account_nm":"영업이익(손실)","thstrm_amount":"200","frmtrm_amount":"-50","currency":"KRW"},
            {"rcept_no":"99990101000001","sj_div":"IS","account_id":"ifrs-full_ProfitLoss","account_nm":"당기순이익","thstrm_amount":"-","frmtrm_amount":"0","currency":"KRW"}
          ]
          """);
    }

    @Test
    void persistsExactNumbersSourcesAndReusesDatabaseAfterServiceRecreation() throws Exception {
        when(client.annualReport("00126380", 2015)).thenReturn(rows());
        var first = service.annual("005930", 2015);
        assertThat(first.metrics().getFirst().currentAmount()).isEqualTo("120000000000001");
        assertThat(first.metrics().getFirst().sourceAccountName()).isEqualTo("수익(매출액)");
        var second = new FinancialService(client, repository).annual("005930", 2015);
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).annualReport("00126380", 2015);
        assertThat(jdbc.queryForObject("SELECT dart_corp_code FROM company WHERE stock_code='005930'", String.class)).isEqualTo("00126380");
        assertThat(second.metrics().get(1).comparison()).isEqualTo("TURNAROUND");
        assertThat(second.metrics().get(2).currentAmount()).isNull();
        assertThat(second.metrics().get(2).previousAmount()).isEqualTo("0");
        assertThat(second.metrics().get(2).status()).isEqualTo("MISSING");
        repository.save(first);
        repository.save(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_report WHERE receipt_no='99990101000001' AND fs_div='CFS'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_value v JOIN financial_report r ON r.report_id=v.report_id WHERE r.receipt_no='99990101000001'", Integer.class)).isEqualTo(3);
    }

    @Test
    void preservesAmbiguousAndForeignCurrencyStatusesAndRejectsMissingReceipt() throws Exception {
        var data = json.readTree("""
          [
           {"rcept_no":"99990101000002","sj_div":"IS","account_id":"ifrs-full_Revenue","thstrm_amount":"100","currency":"KRW"},
           {"rcept_no":"99990101000002","sj_div":"IS","account_id":"ifrs-full_Revenue","thstrm_amount":"200","currency":"KRW"},
           {"rcept_no":"99990101000002","sj_div":"IS","account_id":"dart_OperatingIncomeLoss","thstrm_amount":"50","currency":"USD"}
          ]
          """);
        repository.save(FinancialService.normalize("000660", "00164779", 2015, data));
        var loaded = repository.latest("000660", 2015);
        assertThat(loaded.metrics()).extracting(FinancialReport.Metric::status)
                .containsExactly("AMBIGUOUS", "UNSUPPORTED_CURRENCY", "MISSING");
        assertThat(loaded.metrics().get(1).currency()).isEqualTo("USD");
        assertThat(loaded.metrics()).allMatch(value -> value.currentAmount() == null);
        assertThatThrownBy(() -> repository.save(FinancialService.normalize("005930", "00126380", 2015, json.readTree("[]"))))
                .isInstanceOf(FinancialException.class);
    }
}
