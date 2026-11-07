package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hk.stock.dtos.OpinionDto;
import com.hk.stock.dtos.OpinionReportDto;

//투자의견 분류·증권사별 최신 의견 집계·월별 추세 확인 (네트워크 없이 실행)
class OpinionCalculatorTests {

	private static OpinionReportDto report(String date, String broker, String opinion) {
		return OpinionReportDto.builder().date(date).broker(broker).opinion(opinion).build();
	}

	@Test
	void 증권사마다_다른_표기를_5단계로_나누고_모르는_문구는_기타() {
		assertEquals("strongBuy", OpinionCalculator.categorize("Strong BUY"));
		assertEquals("buy", OpinionCalculator.categorize("BUY"));
		assertEquals("buy", OpinionCalculator.categorize("매수"));
		assertEquals("buy", OpinionCalculator.categorize("Outperform"));
		assertEquals("hold", OpinionCalculator.categorize("중립"));
		assertEquals("sell", OpinionCalculator.categorize("Underperform"));
		assertEquals("strongSell", OpinionCalculator.categorize("적극매도"));
		assertEquals("other", OpinionCalculator.categorize("Not Rated"));
		assertEquals("other", OpinionCalculator.categorize(""));
	}

	@Test
	void 점수와_분포는_증권사별_가장_최근_리포트만_쓴다() {
		//최신순. A증권은 매수 → 중립으로 바꿨으므로 최신 의견(중립)만 1표
		List<OpinionReportDto> reports = new ArrayList<>(List.of(
				report("2026-10-30", "A증권", "중립"),
				report("2026-10-20", "B증권", "Strong BUY"),
				report("2026-09-10", "A증권", "BUY"),
				report("2026-06-01", "C증권", "매수"),
				report("2026-05-15", "D증권", "Not Rated")));

		OpinionDto opinion = OpinionCalculator.summarize("005930", "KIS",
				LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 31), reports);

		assertEquals(5, opinion.getReportCount());
		assertEquals(4, opinion.getBrokerCount());
		assertEquals(1, opinion.getLatest().getHold());
		assertEquals(1, opinion.getLatest().getStrongBuy());
		assertEquals(1, opinion.getLatest().getBuy());
		assertEquals(1, opinion.getLatest().getOther());
		assertEquals(67, opinion.getScore());	//매수 2 / 분류된 3 (기타 제외)
	}

	@Test
	void 월별_추세는_리포트가_없는_달도_0건으로_채운다() {
		List<OpinionReportDto> reports = new ArrayList<>(List.of(
				report("2026-10-30", "A증권", "BUY"),
				report("2026-10-02", "B증권", "BUY"),
				report("2026-07-10", "A증권", "중립")));

		OpinionDto opinion = OpinionCalculator.summarize("005930", "KIS",
				LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 31), reports);

		assertEquals(6, opinion.getMonths().size());
		assertEquals("2026-05", opinion.getMonths().get(0).getPeriod());
		assertEquals(0, opinion.getMonths().get(0).getBuy());
		assertEquals(1, opinion.getMonths().get(2).getHold());	//7월
		assertEquals(2, opinion.getMonths().get(5).getBuy());	//10월은 그달 리포트 수만큼
	}

	@Test
	void 리포트가_없으면_점수는_null이고_월별은_0건() {
		OpinionDto opinion = OpinionCalculator.summarize("005930", "KIS",
				LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 31), new ArrayList<>());

		assertNull(opinion.getScore());
		assertEquals(0, opinion.getBrokerCount());
		assertEquals(6, opinion.getMonths().size());
	}
}
