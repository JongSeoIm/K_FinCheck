package com.hk.stock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hk.stock.dtos.EarningsDto;
import com.hk.stock.dtos.FinancialRowDto;

/**
 * KIS 분기 재무(그해 누적값) → 분기 단독 실적 (네트워크 없이 계산만 한다)
 * - 분기 값 = 이번 분기 누적 - 직전 분기 누적 (1분기는 누적값 그대로). 직전 분기가 없으면 계산하지 않고 null.
 * - 영업이익률 = 영업이익 / 매출액 × 100. 매출이 없거나 0 이하이면 null (0%로 표시하지 않는다).
 * - 12월 결산 법인 기준이다 (03·06·09·12월 → 1~4분기).
 */
public final class EarningsCalculator {

	private EarningsCalculator() {}

	//최신순으로 limit 개
	public static List<EarningsDto> quarters(List<FinancialRowDto> cumulativeRows, int limit) {
		Map<String, FinancialRowDto> byMonth = new HashMap<>();
		for (FinancialRowDto r : cumulativeRows) {
			byMonth.put(r.getYearMonth(), r);
		}

		List<String> months = new ArrayList<>(byMonth.keySet());
		months.sort((a, b) -> b.compareTo(a));

		List<EarningsDto> result = new ArrayList<>();
		for (String ym : months) {
			if (result.size() >= limit) break;
			int year = Integer.parseInt(ym.substring(0, 4));
			int month = Integer.parseInt(ym.substring(4));
			if (month % 3 != 0) continue;
			int quarter = month / 3;

			Standalone now = standalone(byMonth, year, quarter);
			result.add(EarningsDto.builder()
					.period(year + " Q" + quarter)
					.year(year)
					.quarter(quarter)
					.revenue(now.revenue)
					.operatingProfit(now.profit)
					.operatingMargin(margin(now.profit, now.revenue))
					.eps(now.eps)
					.build());
		}
		return result;
	}

	//해당 분기 단독 값
	private static Standalone standalone(Map<String, FinancialRowDto> byMonth, int year, int quarter) {
		FinancialRowDto current = byMonth.get(key(year, quarter));
		if (current == null) return new Standalone(null, null, null);
		if (quarter == 1) return new Standalone(current.getRevenue(), current.getOperatingProfit(), current.getEps());

		FinancialRowDto previous = byMonth.get(key(year, quarter - 1));
		if (previous == null) return new Standalone(null, null, null);
		return new Standalone(
				minus(current.getRevenue(), previous.getRevenue()),
				minus(current.getOperatingProfit(), previous.getOperatingProfit()),
				minus(current.getEps(), previous.getEps()));
	}

	private static String key(int year, int quarter) {
		return String.format("%d%02d", year, quarter * 3);
	}

	private static BigDecimal minus(BigDecimal a, BigDecimal b) {
		return a == null || b == null ? null : a.subtract(b);
	}

	static BigDecimal margin(BigDecimal profit, BigDecimal revenue) {
		if (profit == null || revenue == null || revenue.signum() <= 0) return null;
		return profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
	}

	private record Standalone(BigDecimal revenue, BigDecimal profit, BigDecimal eps) {
	}
}
