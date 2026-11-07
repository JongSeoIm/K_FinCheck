package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hk.stock.dtos.AnnualEarningsDto;
import com.hk.stock.dtos.FinancialResultDto;

class FinancialMetricsTests {

	private static FinancialResultDto.Item item(String code, Long current, Long previous) {
		return FinancialResultDto.Item.builder().accountId(code).current(current).previous(previous).build();
	}

	private static AnnualEarningsDto.Metric metric(List<AnnualEarningsDto.Metric> metrics, String code) {
		return metrics.stream().filter(m -> m.getCode().equals(code)).findFirst().orElseThrow();
	}

	@Test
	void 재무_지표_4종을_계산한다() {
		List<AnnualEarningsDto.Metric> metrics = FinancialMetrics.metrics(List.of(
				item("REVENUE", 1000L, 800L),
				item("OPERATING_INCOME", 150L, 100L),
				item("TOTAL_LIABILITIES", 400L, null),
				item("TOTAL_EQUITY", 500L, null),
				item("CURRENT_ASSETS", 300L, null),
				item("CURRENT_LIABILITIES", 200L, null)));

		assertEquals(new BigDecimal("15.00"), metric(metrics, "OPERATING_MARGIN").getValue());
		assertEquals(new BigDecimal("80.00"), metric(metrics, "DEBT_RATIO").getValue());
		assertEquals(new BigDecimal("150.00"), metric(metrics, "CURRENT_RATIO").getValue());
		assertEquals(new BigDecimal("25.00"), metric(metrics, "REVENUE_GROWTH").getValue());
	}

	@Test
	void 분모가_0이나_음수면_계산_불가_값이_없으면_자료_없음이다() {
		List<AnnualEarningsDto.Metric> metrics = FinancialMetrics.metrics(new ArrayList<>(List.of(
				item("REVENUE", 0L, -5L),
				item("OPERATING_INCOME", 10L, 1L),
				item("TOTAL_LIABILITIES", 400L, null),
				item("TOTAL_EQUITY", -1L, null),
				item("CURRENT_ASSETS", 300L, null))));

		assertEquals("NOT_CALCULABLE", metric(metrics, "OPERATING_MARGIN").getStatus());
		assertNull(metric(metrics, "OPERATING_MARGIN").getValue());
		assertEquals("NOT_CALCULABLE", metric(metrics, "DEBT_RATIO").getStatus());
		assertEquals("MISSING", metric(metrics, "CURRENT_RATIO").getStatus());
		assertEquals("NOT_CALCULABLE", metric(metrics, "REVENUE_GROWTH").getStatus());
	}

	@Test
	void 흑자_적자_전환과_비교_불가를_구분한다() {
		assertEquals("NORMAL", FinancialMetrics.compareStatus(120L, 100L));
		assertEquals(new BigDecimal("20.00"), FinancialMetrics.changeRate(120L, 100L));
		assertEquals("TURN_PROFIT", FinancialMetrics.compareStatus(50L, -10L));
		assertNull(FinancialMetrics.changeRate(50L, -10L));
		assertEquals("TURN_LOSS", FinancialMetrics.compareStatus(-50L, 10L));
		assertNull(FinancialMetrics.changeRate(-50L, 10L));
		assertEquals("LOSS_CONTINUE", FinancialMetrics.compareStatus(-50L, -10L));
		assertEquals("NOT_COMPARABLE", FinancialMetrics.compareStatus(null, 10L));
		assertEquals("NOT_COMPARABLE", FinancialMetrics.compareStatus(10L, 0L));
	}

	@Test
	void 누락된_실적은_null로_둔다() {
		List<AnnualEarningsDto.Item> items = FinancialMetrics.earnings(List.of(item("REVENUE", 100L, 90L)));
		assertEquals(3, items.size());
		assertNull(items.get(1).getCurrent());
		assertEquals("NOT_COMPARABLE", items.get(1).getCompareStatus());
	}
}
