package com.hk.stock.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.ChartDto;
import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.market.MarketDataException;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;

/**
 * 차트 데이터
 * - 초봉(1s·5s·30s): 서버 메모리의 체결 집계. 연결 후 수신분만 있고 재시작하면 사라진다
 * - 분봉(1m~60m): KIS 당일 1분봉을 묶은 값 + 수신 중인 봉
 * - 일·주·월·연봉: KIS 기간별 조회
 * 외부 API 호출 수를 줄이기 위해 메모리에 잠시 보관한다.
 */
@Service
public class ChartService {

	private static final Logger log = LoggerFactory.getLogger(ChartService.class);
	private static final long MINUTE_TTL_OPEN_MS = 30_000;
	private static final long MINUTE_TTL_CLOSED_MS = 10 * 60_000;
	private static final long DAILY_TTL_MS = 10 * 60_000;
	private static final int MAX_POINTS = 1_000;
	private static final String INTERVAL_MESSAGE = "지원하지 않는 캔들 간격입니다: %s (1s, 5s, 30s, 1m, 3m, 5m, 10m, 15m, 30m, 60m, 1d, 1w, 1mo, 1y)";

	@Autowired
	private MarketDataProvider provider;
	@Autowired
	private StockService stockService;
	@Autowired
	private QuoteStore quoteStore;

	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	//선 차트용 이력 (/history): 1D 분봉, 1W·1M·1Y 일봉
	public ChartDto getHistory(String symbol, String range) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		String r = range == null ? "1D" : range.toUpperCase();
		if ("1D".equals(r)) return new ChartDto(symbol, r, "1m", provider.mode(), minutes(symbol));
		return daily(symbol, r, "지원하지 않는 기간입니다: " + range + " (1D, 1W, 1M, 1Y)");
	}

	//캔들 OHLC (/candles). range 는 일봉에서만 사용한다 (1W·1M·1Y)
	public ChartDto getCandles(String symbol, String interval, String range) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		String code = interval == null ? "1d" : interval.toLowerCase();
		Integer seconds = QuoteStore.INTERVALS.get(code);
		if (seconds != null) {
			List<ChartPointDto> points = seconds < 60 ? quoteStore.candles(symbol, code) : minuteCandles(symbol, code, seconds);
			return new ChartDto(symbol, null, code, provider.mode(), points);
		}
		return switch (code) {
			case "1d" -> daily(symbol, range == null ? "1Y" : range.toUpperCase(), "캔들 일봉은 1W, 1M, 1Y 기간만 지원합니다: " + range);
			case "1w" -> period(symbol, code, "W", 5);
			case "1mo" -> period(symbol, code, "M", 10);
			case "1y" -> period(symbol, code, "Y", 20);
			default -> throw new IllegalArgumentException(INTERVAL_MESSAGE.formatted(interval));
		};
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

	private ChartDto period(String symbol, String code, String period, int years) {
		LocalDate today = LocalDate.now(MarketHours.KST);
		List<ChartPointDto> points = cached(code + ":" + symbol, DAILY_TTL_MS,
				() -> provider.fetchPeriod(symbol, period, today.minusYears(years), today));
		return new ChartDto(symbol, null, code, provider.mode(), points);
	}

	//KIS 당일 1분봉을 묶고 수신 중인 봉을 합친다. KIS 조회가 실패해도 수신한 봉으로 응답한다
	private List<ChartPointDto> minuteCandles(String symbol, String code, int seconds) {
		List<ChartPointDto> oneMinute = List.of();
		try {
			oneMinute = minutes(symbol);
		} catch (MarketDataException e) {
			log.warn("당일 분봉 조회 실패, 수신한 봉으로 응답 ({}): {}", symbol, e.getMessage());
		}
		List<ChartPointDto> points = CandleRollup.merge(CandleRollup.rollup(oneMinute, seconds), quoteStore.candles(symbol, code));
		return points.size() > MAX_POINTS ? new ArrayList<>(points.subList(points.size() - MAX_POINTS, points.size())) : points;
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
