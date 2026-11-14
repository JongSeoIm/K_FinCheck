package com.kfincheck.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialEstimateServiceTest {
    @Test
    void usesOnlyEstimateColumnsAndReadsAmountsWithoutTreatingMissingAsZero() throws Exception {
        var body = new ObjectMapper().readTree("""
                {"rt_cd":"0","output1":{"sht_cd":"A035420","estdate":"20261019"},
                 "output2":[{"data1":"900","data2":"1,000"},{"data2":"11"},
                            {"data2":"-25"},{"data2":"12"},{"data2":""},{"data2":"13"}],
                 "output4":[{"dt":"2025.12"},{"dt":"2026.12E"}]}
                """);

        var feed = FinancialEstimateService.normalize("035420", body, Instant.EPOCH);
        assertThat(feed.estimateDate()).hasToString("2026-10-19");
        assertThat(feed.estimates()).singleElement().satisfies(estimate -> {
            assertThat(estimate.closingMonth()).isEqualTo("2026.12");
            assertThat(estimate.revenue()).isEqualByComparingTo("1000");
            assertThat(estimate.operatingProfit()).isEqualByComparingTo("-25");
            assertThat(estimate.netIncome()).isNull();
        });
    }

    @Test
    void acceptsSuccessfulResponsesWithoutEstimates() throws Exception {
        var body = new ObjectMapper().readTree("""
                {"rt_cd":"0","output1":{"sht_cd":""},"output2":[],"output4":[]}
                """);
        assertThat(FinancialEstimateService.normalize("005930", body, Instant.EPOCH).estimates()).isEmpty();
    }
}
