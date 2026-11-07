package com.hk.stock.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.OpinionDto;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;

/**
 * 전문가 투자의견 (KIS 국내주식 종목투자의견)
 * - 이번 달을 포함한 최근 MONTHS 개월의 증권사 리포트를 받아 OpinionCalculator 로 요약한다.
 * - 투자의견은 하루에 몇 번 바뀌지 않으므로 종목별로 TTL 동안 저장해 두고 KIS 호출 한도를 아낀다.
 */
@Service
public class OpinionService {

	private static final int MONTHS = 6;
	private static final long TTL_MS = 60 * 60_000;

	@Autowired
	private MarketDataProvider provider;
	@Autowired
	private StockService stockService;

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public OpinionDto getOpinion(String symbol) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		Cached cached = cache.get(symbol);
		if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL_MS) {
			return cached.opinion;
		}

		LocalDate to = LocalDate.now(MarketHours.KST);
		LocalDate from = to.withDayOfMonth(1).minusMonths(MONTHS - 1);
		OpinionDto opinion = OpinionCalculator.summarize(symbol, provider.mode(), from, to,
				provider.fetchOpinions(symbol, from, to));
		cache.put(symbol, new Cached(opinion, System.currentTimeMillis()));
		return opinion;
	}

	private record Cached(OpinionDto opinion, long fetchedAt) {
	}
}
