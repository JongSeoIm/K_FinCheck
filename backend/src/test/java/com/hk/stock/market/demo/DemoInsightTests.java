package com.hk.stock.market.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hk.stock.dtos.EarningsDto;
import com.hk.stock.dtos.OpinionDto;
import com.hk.stock.service.EarningsCalculator;
import com.hk.stock.service.OpinionCalculator;

//KIS 키가 없는 팀원도 투자의견·실적 패널을 볼 수 있는지 확인 (모의 데이터)
class DemoInsightTests {

	private final DemoMarketDataProvider demo = new DemoMarketDataProvider();

	@Test
	void 모의_투자의견은_기간_안의_리포트로_요약된다() {
		LocalDate from = LocalDate.of(2026, 6, 1);
		LocalDate to = LocalDate.of(2026, 11, 2);
		OpinionDto opinion = OpinionCalculator.summarize("005930", "DEMO", from, to, demo.fetchOpinions("005930", from, to));

		assertTrue(opinion.getReportCount() > 20);
		assertTrue(opinion.getBrokerCount() > 0);
		assertNotNull(opinion.getScore());
		assertEquals(0, opinion.getLatest().getOther());	//모의 의견 문구는 모두 분류된다
	}

	@Test
	void 모의_재무는_최근_4분기_실적으로_계산된다() {
		List<EarningsDto> quarters = EarningsCalculator.quarters(demo.fetchQuarterlyFinancials("000660"), 4);

		assertEquals(4, quarters.size());
		quarters.forEach(q -> {
			assertNotNull(q.getRevenue());
			assertNotNull(q.getOperatingMargin());
		});
	}
}
