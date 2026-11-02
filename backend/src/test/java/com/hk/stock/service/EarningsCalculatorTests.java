package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hk.stock.dtos.EarningsDto;
import com.hk.stock.dtos.FinancialRowDto;

//분기 누적값 → 분기 단독 실적 계산 확인 (삼성전자 KIS 응답 값 일부 사용, 단위 억원)
class EarningsCalculatorTests {

	private static FinancialRowDto row(String ym, String revenue, String profit, String eps) {
		return new FinancialRowDto(ym,
				revenue == null ? null : new BigDecimal(revenue),
				profit == null ? null : new BigDecimal(profit),
				eps == null ? null : new BigDecimal(eps));
	}

	private static final List<FinancialRowDto> SAMSUNG = List.of(
			row("202606", "3053729", "1467252", "17687"),
			row("202603", "1338734", "572328", "6993"),
			row("202512", "3336059", "436011", "6564"),
			row("202509", "2397686", "235274", "3701"),
			row("202506", "1537068", "113613", "1920"),
			row("202503", "791405", "66853", "1186"));

	@Test
	void 누적값에서_직전_분기를_빼서_분기_단독_값을_구한다() {
		List<EarningsDto> quarters = EarningsCalculator.quarters(SAMSUNG, 4);

		assertEquals(4, quarters.size());
		EarningsDto q2 = quarters.get(0);
		assertEquals("2026 Q2", q2.getPeriod());
		assertEquals(new BigDecimal("1714995"), q2.getRevenue());
		assertEquals(new BigDecimal("894924"), q2.getOperatingProfit());
		assertEquals(new BigDecimal("10694"), q2.getEps());
		assertEquals(new BigDecimal("52.2"), q2.getOperatingMargin());
	}

	@Test
	void 일분기는_누적값을_그대로_쓰고_4분기는_연간에서_3분기_누적을_뺀다() {
		List<EarningsDto> quarters = EarningsCalculator.quarters(SAMSUNG, 4);

		assertEquals("2026 Q1", quarters.get(1).getPeriod());
		assertEquals(new BigDecimal("1338734"), quarters.get(1).getRevenue());
		assertEquals(new BigDecimal("42.8"), quarters.get(1).getOperatingMargin());
		assertEquals("2025 Q4", quarters.get(2).getPeriod());
		assertEquals(new BigDecimal("938373"), quarters.get(2).getRevenue());
		assertEquals(new BigDecimal("21.4"), quarters.get(2).getOperatingMargin());
	}

	@Test
	void SK하이닉스_2분기는_회사_발표_실적과_같다() {
		//KIS 누적값: 2026.03 매출 525,763억·영업이익 376,103억 / 2026.06 매출 1,318,950억·영업이익 981,529억
		//회사 발표(2026.07.29, 연결): 2분기 매출 79조3,187억, 영업이익 60조5,426억, 영업이익률 약 76%
		EarningsDto q2 = EarningsCalculator.quarters(List.of(
				row("202606", "1318950", "981529", "187361"),
				row("202603", "525763", "376103", "56066")), 1).get(0);

		assertEquals(new BigDecimal("793187"), q2.getRevenue());
		assertEquals(new BigDecimal("605426"), q2.getOperatingProfit());
		assertEquals(new BigDecimal("76.3"), q2.getOperatingMargin());
	}

	@Test
	void 직전_분기_누적이나_매출이_없으면_0이_아니라_null() {
		List<EarningsDto> quarters = EarningsCalculator.quarters(List.of(
				row("202606", "1000", "100", "10"),		//202603 없음 → 계산 불가
				row("202503", null, "50", "5")), 4);	//매출 없음 → 영업이익률 계산 불가

		assertNull(quarters.get(0).getRevenue());
		assertNull(quarters.get(0).getOperatingMargin());
		assertEquals(new BigDecimal("50"), quarters.get(1).getOperatingProfit());
		assertNull(quarters.get(1).getOperatingMargin());
	}

	@Test
	void 영업손실이면_영업이익률은_음수이고_매출이_0이면_null() {
		assertEquals(new BigDecimal("-12.5"), EarningsCalculator.margin(new BigDecimal("-100"), new BigDecimal("800")));
		assertNull(EarningsCalculator.margin(new BigDecimal("10"), BigDecimal.ZERO));
	}
}
