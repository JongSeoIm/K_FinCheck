package com.hk.stock.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.EarningsDto;
import com.hk.stock.market.MarketDataProvider;

/**
 * 최근 실적 (KIS 분기 손익계산서·재무비율)
 * - 최근 QUARTERS 개 분기의 매출액·영업이익·영업이익률·EPS
 * - 재무 자료는 분기마다 바뀌므로 종목별로 TTL 동안 저장해 둔다.
 */
@Service
public class EarningsService {

	public static final String UNIT = "억원";
	private static final int QUARTERS = 4;
	private static final long TTL_MS = 6 * 60 * 60_000;

	@Autowired
	private MarketDataProvider provider;
	@Autowired
	private StockService stockService;

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public List<EarningsDto> getEarnings(String symbol) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		Cached cached = cache.get(symbol);
		if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL_MS) {
			return cached.quarters;
		}

		List<EarningsDto> quarters = EarningsCalculator.quarters(provider.fetchQuarterlyFinancials(symbol), QUARTERS);
		cache.put(symbol, new Cached(quarters, System.currentTimeMillis()));
		return quarters;
	}

	public String source() {
		return provider.mode();
	}

	private record Cached(List<EarningsDto> quarters, long fetchedAt) {
	}
}
