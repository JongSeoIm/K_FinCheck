package com.hk.stock.market.demo;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.FinancialRowDto;
import com.hk.stock.dtos.OpinionReportDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;

/**
 * 모의 시세 제공처 (KIS 키가 없을 때 사용)
 * - 실제 시세가 아니다. 응답의 source 가 "DEMO" 이고 화면에도 "모의 데이터"로 표시된다.
 * - 장외 시간·주말에도 화면·전달 흐름을 개발하고 확인할 수 있도록 1초마다 가상 체결을 만든다.
 * - 같은 종목은 서버를 다시 켜도 같은 과거 일봉이 나오도록 종목코드로 난수 시드를 고정한다.
 */
public class DemoMarketDataProvider implements MarketDataProvider {

	private static final Map<String, Long> BASE_PRICES = Map.of(
			"005930", 71_000L,
			"000660", 190_000L);

	private static final String[] DEMO_BROKERS = { "가상증권A", "가상증권B", "가상증권C", "가상증권D", "가상증권E", "가상증권F" };

	private final Map<String, List<ChartPointDto>> dailyCache = new ConcurrentHashMap<>();
	private final Map<String, LiveState> live = new ConcurrentHashMap<>();
	private final Random random = new Random();
	private ScheduledExecutorService scheduler;

	@Override
	public String mode() {
		return "DEMO";
	}

	@Override
	public QuoteDto fetchQuote(String symbol) {
		return state(symbol).toQuote(symbol);
	}

	@Override
	public List<ChartPointDto> fetchTodayMinutes(String symbol) {
		ZonedDateTime now = MarketHours.now();
		LocalDate day = lastTradingDay(now);
		LocalTime end = (day.equals(now.toLocalDate()) && MarketHours.isOpen(now))
				? now.toLocalTime()
				: MarketHours.CLOSE;

		LiveState s = state(symbol);
		Random r = new Random(symbol.hashCode() * 31L + day.toEpochDay());
		long price = s.open;
		List<ChartPointDto> points = new ArrayList<>();
		for (LocalTime t = MarketHours.OPEN; t.isBefore(end); t = t.plusMinutes(1)) {
			long open = price;
			//전일 종가 쪽으로 약하게 되돌아오는 랜덤 워크 (하루 변동폭이 과하지 않게)
			double pull = (s.previousClose - price) * 0.02;
			long close = roundToTick(Math.max(1, price + (long) (pull + r.nextGaussian() * tickSize(price) * 0.7)));
			long high = Math.max(open, close) + tickSize(price) * r.nextInt(2);
			long low = Math.min(open, close) - tickSize(price) * r.nextInt(2);
			points.add(new ChartPointDto(
					ZonedDateTime.of(day, t, MarketHours.KST).toOffsetDateTime().toString(),
					bd(open), bd(high), bd(low), bd(close), (long) (1000 + r.nextInt(20000))));
			price = close;
		}
		return points;
	}

	@Override
	public List<ChartPointDto> fetchDaily(String symbol, LocalDate from, LocalDate to) {
		List<ChartPointDto> all = dailyCache.computeIfAbsent(symbol, this::generateDaily);
		List<ChartPointDto> result = new ArrayList<>();
		for (ChartPointDto p : all) {
			LocalDate d = LocalDate.parse(p.getTime());
			if (!d.isBefore(from) && !d.isAfter(to)) result.add(p);
		}
		//오늘 봉은 현재 가상 체결 상태로 갱신
		LocalDate today = MarketHours.now().toLocalDate();
		if (MarketHours.isWeekday(MarketHours.now()) && !today.isBefore(from) && !today.isAfter(to)) {
			LiveState s = state(symbol);
			result.removeIf(p -> p.getTime().equals(today.toString()));
			result.add(new ChartPointDto(today.toString(), bd(s.open), bd(s.high), bd(s.low), bd(s.price), s.volume));
		}
		return result;
	}

	//가상 증권사 리포트: 4일에 1건꼴, 의견은 매수 위주 (실제 국내 리포트 분포와 비슷하게)
	@Override
	public List<OpinionReportDto> fetchOpinions(String symbol, LocalDate from, LocalDate to) {
		Random r = new Random(symbol.hashCode() * 17L);
		long base = BASE_PRICES.getOrDefault(symbol, 50_000L);
		List<OpinionReportDto> result = new ArrayList<>();
		for (LocalDate d = to; !d.isBefore(from); d = d.minusDays(2 + r.nextInt(5))) {
			int pick = r.nextInt(20);
			String opinion = pick < 2 ? "Strong BUY" : pick < 15 ? "BUY" : pick < 19 ? "중립" : "매도";
			long target = roundToTick(base + (long) (base * (0.15 + r.nextDouble() * 0.35)));
			result.add(OpinionReportDto.builder()
					.date(d.toString())
					.broker(DEMO_BROKERS[r.nextInt(DEMO_BROKERS.length)])
					.opinion(opinion)
					.targetPrice(BigDecimal.valueOf(target))
					.build());
		}
		return result;
	}

	//가상 분기 재무 (누적값, 최신순): 최근 3년 12분기
	@Override
	public List<FinancialRowDto> fetchQuarterlyFinancials(String symbol) {
		Random r = new Random(symbol.hashCode() * 29L);
		long scale = BASE_PRICES.getOrDefault(symbol, 50_000L) / 100;	//종목마다 규모를 다르게
		int lastYear = MarketHours.now().getYear();
		List<FinancialRowDto> rows = new ArrayList<>();
		for (int year = lastYear - 2; year <= lastYear; year++) {
			long revenue = 0, profit = 0, eps = 0;
			for (int q = 1; q <= 4; q++) {
				if (year == lastYear && q > 2) break;	//올해는 2분기까지 공시된 것으로 가정
				long quarterRevenue = scale * (600 + (year - lastYear + 2) * 60 + r.nextInt(120));
				long quarterProfit = quarterRevenue * (8 + r.nextInt(18)) / 100;
				revenue += quarterRevenue;
				profit += quarterProfit;
				eps += quarterProfit * 10 / scale;
				rows.add(new FinancialRowDto(String.format("%d%02d", year, q * 3),
						BigDecimal.valueOf(revenue), BigDecimal.valueOf(profit), BigDecimal.valueOf(eps)));
			}
		}
		rows.sort(Comparator.comparing(FinancialRowDto::getYearMonth).reversed());
		return rows;
	}

	@Override
	public synchronized void startRealtime(List<String> symbols, Consumer<QuoteDto> onTick) {
		symbols.forEach(this::state);
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "demo-ticks");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleAtFixedRate(() -> {
			for (String symbol : symbols) {
				if (random.nextInt(10) < 6) {	//매초 약 60% 확률로 체결 발생
					LiveState s = state(symbol);
					s.tick(random);
					onTick.accept(s.toQuote(symbol));
				}
			}
		}, 1, 1, TimeUnit.SECONDS);
	}

	@Override
	public synchronized void stop() {
		if (scheduler != null) scheduler.shutdownNow();
	}

	@Override
	public FeedStatus feedStatus() {
		return FeedStatus.DEMO;
	}

	private LiveState state(String symbol) {
		return live.computeIfAbsent(symbol, key -> {
			List<ChartPointDto> daily = dailyCache.computeIfAbsent(key, this::generateDaily);
			long prevClose = daily.get(daily.size() - 1).getClose().longValue();
			return new LiveState(prevClose);
		});
	}

	//오늘 이전 거래일까지 약 2년치 일봉 (오래된 순)
	private List<ChartPointDto> generateDaily(String symbol) {
		Random r = new Random(symbol.hashCode());
		long base = BASE_PRICES.getOrDefault(symbol, 50_000L);
		LocalDate today = MarketHours.now().toLocalDate();
		List<ChartPointDto> reversed = new ArrayList<>();
		long close = base;
		for (LocalDate d = today.minusDays(1); d.isAfter(today.minusYears(2)); d = d.minusDays(1)) {
			if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
			long open = roundToTick(close + (long) (r.nextGaussian() * close * 0.006));
			long high = roundToTick(Math.max(open, close) + (long) (Math.abs(r.nextGaussian()) * close * 0.008));
			long low = roundToTick(Math.min(open, close) - (long) (Math.abs(r.nextGaussian()) * close * 0.008));
			reversed.add(new ChartPointDto(d.toString(), bd(open), bd(high), bd(low), bd(close),
					(long) (5_000_000 + r.nextInt(15_000_000))));
			//과거로 갈수록 이전 날 종가를 역으로 만든다
			close = roundToTick(Math.max(1000, open + (long) (r.nextGaussian() * close * 0.004)));
		}
		List<ChartPointDto> result = new ArrayList<>();
		for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
		return result;
	}

	private static LocalDate lastTradingDay(ZonedDateTime now) {
		LocalDate d = now.toLocalDate();
		if (MarketHours.isWeekday(now) && now.toLocalTime().isBefore(MarketHours.OPEN)) d = d.minusDays(1);
		while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
		return d;
	}

	//국내 주식 호가 단위
	static long tickSize(long price) {
		if (price < 2_000) return 1;
		if (price < 5_000) return 5;
		if (price < 20_000) return 10;
		if (price < 50_000) return 50;
		if (price < 200_000) return 100;
		if (price < 500_000) return 500;
		return 1_000;
	}

	static long roundToTick(long price) {
		long tick = tickSize(price);
		return Math.round((double) price / tick) * tick;
	}

	private static BigDecimal bd(long v) {
		return BigDecimal.valueOf(v);
	}

	//종목별 가상 체결 상태
	private static class LiveState {
		final long previousClose;
		long price;
		long open;
		long high;
		long low;
		long volume;

		LiveState(long previousClose) {
			this.previousClose = previousClose;
			this.price = previousClose;
			this.open = previousClose;
			this.high = previousClose;
			this.low = previousClose;
			this.volume = 0;
		}

		synchronized void tick(Random r) {
			long step = tickSize(price) * (r.nextInt(5) - 2);	//-2 ~ +2 호가
			price = Math.max(tickSize(price), price + step);
			high = Math.max(high, price);
			low = Math.min(low, price);
			volume += 1 + r.nextInt(500);
		}

		synchronized QuoteDto toQuote(String symbol) {
			BigDecimal change = bd(price - previousClose);
			BigDecimal rate = change.multiply(BigDecimal.valueOf(100))
					.divide(bd(previousClose), 2, java.math.RoundingMode.HALF_UP);
			return QuoteDto.builder()
					.symbol(symbol)
					.price(bd(price))
					.changeAmount(change)
					.changeRate(rate)
					.open(bd(open))
					.high(bd(high))
					.low(bd(low))
					.previousClose(bd(previousClose))
					.cumulativeVolume(volume)
					.eventTime(MarketHours.now().withNano(0).toOffsetDateTime().toString())
					.source("DEMO")
					.build();
		}
	}
}
