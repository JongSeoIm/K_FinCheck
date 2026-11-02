package com.hk.stock.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hk.stock.dtos.OpinionCountsDto;
import com.hk.stock.dtos.OpinionDto;
import com.hk.stock.dtos.OpinionReportDto;

/**
 * 증권사 리포트별 투자의견 → 화면용 요약 (네트워크 없이 계산만 한다)
 * - 의견 문구를 5단계로 분류한다. 증권사마다 표기가 달라(BUY, 매수, Outperform …) 공백을 지우고 대문자로 맞춘 뒤 비교한다.
 * - 목록에 없는 문구는 중립으로 넣지 않고 "기타"로 따로 센다 (점수 계산에서 제외).
 * - 점수·분포는 증권사별 가장 최근 리포트 1건씩만 사용한다 (같은 증권사가 여러 번 내도 1표).
 * - 월별 추세는 그달에 나온 리포트를 모두 센다.
 */
public final class OpinionCalculator {

	private static final Set<String> STRONG_BUY = Set.of("STRONGBUY", "적극매수", "강력매수");
	private static final Set<String> BUY = Set.of("BUY", "매수", "OUTPERFORM", "OVERWEIGHT", "비중확대", "TRADINGBUY", "ACCUMULATE", "ADD");
	private static final Set<String> HOLD = Set.of("HOLD", "중립", "NEUTRAL", "MARKETPERFORM", "보유");
	private static final Set<String> SELL = Set.of("SELL", "매도", "UNDERPERFORM", "UNDERWEIGHT", "비중축소", "REDUCE");
	private static final Set<String> STRONG_SELL = Set.of("STRONGSELL", "적극매도", "강력매도");

	private OpinionCalculator() {}

	public static String categorize(String opinion) {
		String key = opinion == null ? "" : opinion.replaceAll("\\s+", "").toUpperCase();
		if (STRONG_BUY.contains(key)) return "strongBuy";
		if (BUY.contains(key)) return "buy";
		if (HOLD.contains(key)) return "hold";
		if (SELL.contains(key)) return "sell";
		if (STRONG_SELL.contains(key)) return "strongSell";
		return "other";
	}

	//reports 는 최신순
	public static OpinionDto summarize(String symbol, String source, LocalDate from, LocalDate to,
			List<OpinionReportDto> reports) {
		reports.forEach(r -> r.setCategory(categorize(r.getOpinion())));

		//월별 추세 (리포트가 없는 달도 0건으로 채운다)
		Map<String, OpinionCountsDto> months = new LinkedHashMap<>();
		for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
			months.put(m.toString(), new OpinionCountsDto(m.toString()));
		}
		for (OpinionReportDto r : reports) {
			OpinionCountsDto month = months.get(r.getDate().substring(0, 7));
			if (month != null) month.add(r.getCategory());
		}

		//증권사별 가장 최근 리포트
		Map<String, OpinionReportDto> latestByBroker = new LinkedHashMap<>();
		for (OpinionReportDto r : reports) {
			latestByBroker.putIfAbsent(r.getBroker(), r);
		}
		OpinionCountsDto latest = new OpinionCountsDto();
		for (OpinionReportDto r : latestByBroker.values()) {
			latest.add(r.getCategory());
		}

		Integer score = latest.rated() == 0 ? null
				: Math.round((latest.getStrongBuy() + latest.getBuy()) * 100f / latest.rated());

		return OpinionDto.builder()
				.symbol(symbol)
				.source(source)
				.from(from.toString())
				.to(to.toString())
				.reportCount(reports.size())
				.brokerCount(latestByBroker.size())
				.score(score)
				.latest(latest)
				.months(new ArrayList<>(months.values()))
				.build();
	}
}
