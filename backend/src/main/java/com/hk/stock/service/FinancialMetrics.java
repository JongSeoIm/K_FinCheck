package com.hk.stock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hk.stock.dtos.AnnualEarningsDto;
import com.hk.stock.dtos.FinancialResultDto;

/**
 * 공시 수치 → 전년 대비 비교·재무 지표 (네트워크 없이 계산만 한다)
 * - 같은 공시(같은 연결/별도 기준)의 수치끼리만 계산한다.
 * - 분모가 0·음수이거나 값이 없으면 0%로 표시하지 않고 계산 불가·자료 없음으로 구분한다.
 */
public final class FinancialMetrics {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final List<String[]> EARNINGS = List.of(
			new String[] { FinancialAccounts.REVENUE, "매출액" },
			new String[] { FinancialAccounts.OPERATING_INCOME, "영업이익" },
			new String[] { FinancialAccounts.NET_INCOME, "당기순이익" });

	private FinancialMetrics() {}

	public static List<AnnualEarningsDto.Item> earnings(List<FinancialResultDto.Item> items) {
		Map<String, FinancialResultDto.Item> byCode = index(items);
		List<AnnualEarningsDto.Item> result = new ArrayList<>();
		for (String[] account : EARNINGS) {
			FinancialResultDto.Item item = byCode.get(account[0]);
			Long current = item == null ? null : item.getCurrent();
			Long previous = item == null ? null : item.getPrevious();
			result.add(AnnualEarningsDto.Item.builder()
					.code(account[0])
					.name(account[1])
					.current(current)
					.previous(previous)
					.changeRate(changeRate(current, previous))
					.compareStatus(compareStatus(current, previous))
					.build());
		}
		return result;
	}

	public static List<AnnualEarningsDto.Metric> metrics(List<FinancialResultDto.Item> items) {
		Map<String, FinancialResultDto.Item> byCode = index(items);
		Long revenue = current(byCode, FinancialAccounts.REVENUE);
		Long previousRevenue = previous(byCode, FinancialAccounts.REVENUE);
		return List.of(
				metric("OPERATING_MARGIN", "영업이익률", current(byCode, FinancialAccounts.OPERATING_INCOME), revenue, false),
				metric("DEBT_RATIO", "부채비율", current(byCode, "TOTAL_LIABILITIES"), current(byCode, "TOTAL_EQUITY"), true),
				metric("CURRENT_RATIO", "유동비율", current(byCode, "CURRENT_ASSETS"), current(byCode, "CURRENT_LIABILITIES"), false),
				growth(revenue, previousRevenue));
	}

	//numerator / denominator × 100. positiveOnly 면 분모가 음수일 때도 계산하지 않는다
	static AnnualEarningsDto.Metric metric(String code, String name, Long numerator, Long denominator, boolean positiveOnly) {
		if (numerator == null || denominator == null) return result(code, name, null, "MISSING");
		if (denominator == 0 || (positiveOnly && denominator < 0)) return result(code, name, null, "NOT_CALCULABLE");
		return result(code, name, percent(numerator, denominator), "OK");
	}

	private static AnnualEarningsDto.Metric growth(Long current, Long previous) {
		String code = "REVENUE_GROWTH";
		String name = "매출 증감률";
		if (current == null || previous == null) return result(code, name, null, "MISSING");
		if (previous <= 0) return result(code, name, null, "NOT_CALCULABLE");
		return result(code, name, percent(current - previous, previous), "OK");
	}

	static BigDecimal changeRate(Long current, Long previous) {
		if (current == null || previous == null || previous <= 0 || current < 0) return null;
		return percent(current - previous, previous);
	}

	static String compareStatus(Long current, Long previous) {
		if (current == null || previous == null) return "NOT_COMPARABLE";
		if (previous < 0 && current >= 0) return "TURN_PROFIT";
		if (previous >= 0 && current < 0) return "TURN_LOSS";
		if (previous < 0) return "LOSS_CONTINUE";
		return previous == 0 ? "NOT_COMPARABLE" : "NORMAL";
	}

	private static BigDecimal percent(long numerator, long denominator) {
		return BigDecimal.valueOf(numerator).multiply(HUNDRED)
				.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
	}

	private static AnnualEarningsDto.Metric result(String code, String name, BigDecimal value, String status) {
		return AnnualEarningsDto.Metric.builder().code(code).name(name).value(value).status(status).build();
	}

	private static Map<String, FinancialResultDto.Item> index(List<FinancialResultDto.Item> items) {
		return items.stream().collect(Collectors.toMap(FinancialResultDto.Item::getAccountId, Function.identity(), (a, b) -> a));
	}

	private static Long current(Map<String, FinancialResultDto.Item> byCode, String code) {
		FinancialResultDto.Item item = byCode.get(code);
		return item == null ? null : item.getCurrent();
	}

	private static Long previous(Map<String, FinancialResultDto.Item> byCode, String code) {
		FinancialResultDto.Item item = byCode.get(code);
		return item == null ? null : item.getPrevious();
	}
}
