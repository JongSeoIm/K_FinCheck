package com.hk.stock.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.ChartDto;
import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;

/**
 * 차트 데이터
 * - 1D: 가장 최근 거래일 1분봉
 * - 1W·1M·1Y: 일봉 (1년치를 한 번 받아 두고 기간만 잘라서 사용)
 * 외부 API 호출 수를 줄이기 위해 메모리에 잠시 보관한다.
 */
@Service
public class ChartService {

	private static final long MINUTE_TTL_OPEN_MS = 30_000;
	private static final long MINUTE_TTL_CLOSED_MS = 10 * 60_000;
	private static final long DAILY_TTL_MS = 10 * 60_000;

	@Autowired
	private MarketDataProvider provider;
	@Autowired
	private StockService stockService;

	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	//선 차트용 이력 (/history): 1D 분봉, 1W·1M·1Y 일봉
	public ChartDto getHistory(String symbol, String range) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		String r = range == null ? "1D" : range.toUpperCase();
		if ("1D".equals(r)) return new ChartDto(symbol, r, "1m", provider.mode(), minutes(symbol));
		return daily(symbol, r, "지원하지 않는 기간입니다: " + range + " (1D, 1W, 1M, 1Y)");
	}

	//캔들용 OHLC (/candles): 현재는 일봉(interval=1d)만, 기간 1W·1M·1Y
	public ChartDto getCandles(String symbol, String interval, String range) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		String i = interval == null ? "1d" : interval.toLowerCase();
		if (!"1d".equals(i)) {
			throw new IllegalArgumentException("지원하지 않는 캔들 간격입니다: " + interval + " (1d)");
		}
		String r = range == null ? "1M" : range.toUpperCase();
		return daily(symbol, r, "캔들은 일봉이라 1W, 1M, 1Y 기간만 지원합니다: " + range);
	}

	private ChartDto daily(String symbol, String range, String errorMessage) {
		LocalDate today = LocalDate.now(MarketHours.KST);
		LocalDate from = switch (range) {
			case "1W" -> today.minusWeeks(1);
			case "1M" -> today.minusMonths(1);
			case "1Y" -> today.minusYears(1);
			default -> throw new IllegalArgumentException(errorMessage);
		};
		return new ChartDto(symbol, range, "1d", provider.mode(), dailySince(symbol, from));
	}

	private List<ChartPointDto> minutes(String symbol) {
		long ttl = MarketHours.isOpenNow() ? MINUTE_TTL_OPEN_MS : MINUTE_TTL_CLOSED_MS;
		return cached("min:" + symbol, ttl, () -> provider.fetchTodayMinutes(symbol));
	}

	private List<ChartPointDto> dailySince(String symbol, LocalDate from) {
		LocalDate today = LocalDate.now(MarketHours.KST);
		List<ChartPointDto> year = cached("day:" + symbol, DAILY_TTL_MS,
				() -> provider.fetchDaily(symbol, today.minusYears(1).minusDays(7), today));
		String fromText = from.toString();
		return year.stream().filter(p -> p.getTime().compareTo(fromText) >= 0).toList();
	}

	private List<ChartPointDto> cached(String key, long ttl, java.util.function.Supplier<List<ChartPointDto>> loader) {
		CacheEntry entry = cache.get(key);
		if (entry != null && entry.expiresAt() > System.currentTimeMillis()) {
			return entry.points();
		}
		List<ChartPointDto> points = loader.get();
		cache.put(key, new CacheEntry(System.currentTimeMillis() + ttl, points));
		return points;
	}

	private record CacheEntry(long expiresAt, List<ChartPointDto> points) {
	}
}
