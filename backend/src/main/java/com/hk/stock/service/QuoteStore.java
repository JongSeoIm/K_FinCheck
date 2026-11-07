package com.hk.stock.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.MarketStatusDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.dtos.StockDto;
import com.hk.stock.market.MarketDataException;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;
import com.hk.stock.market.QuoteKafkaProducer;

import jakarta.annotation.PreDestroy;

/**
 * 종목별 최신 시세와 초·분봉을 서버 메모리에 보관한다.
 * - KIS 체결을 Kafka 로 발행하고(서버 시작 시 수신 시작), 소비한 값으로 최신 시세와 봉을 갱신한다.
 * - Polling(REST)과 Push(WebSocket)는 모두 이 최신 상태를 사용한다.
 * - 체결이 한동안 없으면(장외·Kafka 중단) REST 현재가로 보충한다. 복구되면 다시 체결로 갱신된다.
 *
 * 중복·역순 처리: 재시작 후 Kafka 를 다시 읽거나 재시도로 같은 메시지가 와도, 체결 시각과 누적 거래량이 앞서는 값만 반영한다.
 *
 * 봉 집계: 수신한 모든 체결을 체결 시각 구간으로 나눈다. 체결이 없는 구간에는 봉을 만들지 않는다.
 * 봉 거래량은 누적 거래량의 차이이고, 누적이 줄어들면 새 거래일로 보고 기준을 다시 잡는다.
 * 진행 중인 봉은 complete=false, 구간이 끝나면 complete=true 다. 재시작하면 사라진다.
 */
@Service
public class QuoteStore {

	private static final Logger log = LoggerFactory.getLogger(QuoteStore.class);
	private static final long STALE_MS = 60_000;		//이 시간 동안 갱신이 없으면 REST로 보충
	private static final long REST_MIN_GAP_MS = 5_000;	//종목별 REST 보충 최소 간격
	private static final long REST_FAILURE_GAP_MS = 60_000;	//조회 실패한 종목은 1분 뒤에 다시 시도 (권한 없는 종목 등)
	private static final long KAFKA_TIMEOUT_MS = 15_000;	//이 시간 동안 Kafka 확인 메시지가 없으면 끊김으로 표시
	private static final int MAX_BARS = 720;
	//체결 시각과 수신 시각의 차이만큼 기다렸다가 봉을 닫는다 (KIS 체결은 수 초 늦게 도착할 수 있다)
	private static final int SEAL_GRACE_SECONDS = 5;

	public static final String KAFKA_DOWN = "KAFKA_DOWN";
	public static final Map<String, Integer> INTERVALS;

	static {
		Map<String, Integer> map = new LinkedHashMap<>();
		map.put("1s", 1);
		map.put("5s", 5);
		map.put("30s", 30);
		map.put("1m", 60);
		map.put("3m", 180);
		map.put("5m", 300);
		map.put("10m", 600);
		map.put("15m", 900);
		map.put("30m", 1800);
		map.put("60m", 3600);
		INTERVALS = Collections.unmodifiableMap(map);
	}

	private record LastTick(long time, long volume) {
	}

	private static final class Bar {
		final long start;
		BigDecimal open;
		BigDecimal high;
		BigDecimal low;
		BigDecimal close;
		long volume;

		Bar(long start, BigDecimal price) {
			this.start = start;
			this.open = price;
			this.high = price;
			this.low = price;
			this.close = price;
		}

		ChartPointDto toDto(boolean complete) {
			return new ChartPointDto(isoTime(start), open, high, low, close, volume, complete);
		}
	}

	private static final class Series {
		final int seconds;
		final Deque<ChartPointDto> completed = new ArrayDeque<>();
		Bar current;
		long lastSealedStart = Long.MIN_VALUE;

		Series(int seconds) {
			this.seconds = seconds;
		}
	}

	private final MarketDataProvider provider;
	private final StockService stockService;
	private final ApplicationEventPublisher publisher;
	private final QuoteKafkaProducer kafkaProducer;

	private final Map<String, QuoteDto> latest = new ConcurrentHashMap<>();
	private final Map<String, String> names = new ConcurrentHashMap<>();
	private final Map<String, String> markets = new ConcurrentHashMap<>();
	private final AtomicLong eventSeq = new AtomicLong();
	private final Map<String, Long> lastRestFetch = new ConcurrentHashMap<>();
	private final Map<String, Long> lastRestFailure = new ConcurrentHashMap<>();
	private final Map<String, LastTick> lastTicks = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Series>> bars = new HashMap<>();
	private final Map<String, Long> lastVolume = new HashMap<>();
	private volatile Long lastTickAt;
	private volatile long lastKafkaHeartbeat;

	public QuoteStore(MarketDataProvider provider, StockService stockService,
			ApplicationEventPublisher publisher, QuoteKafkaProducer kafkaProducer) {
		this.provider = provider;
		this.stockService = stockService;
		this.publisher = publisher;
		this.kafkaProducer = kafkaProducer;
	}

	//서버 준비가 끝나면 지원 종목의 현재가를 채우고 실시간 수신을 시작한다. 받은 체결은 Kafka 로 발행한다
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		List<StockDto> stocks = stockService.getAllStocks();
		stocks.forEach(stock -> {
			names.put(stock.getSymbol(), stock.getName());
			if (stock.getMarket() != null) markets.put(stock.getSymbol(), stock.getMarket());
		});
		for (StockDto stock : stocks) {
			try {
				refreshFromRest(stock.getSymbol());
			} catch (MarketDataException e) {
				log.warn("초기 현재가 조회 실패 ({}): {}", stock.getSymbol(), e.getMessage());
			}
		}
		provider.startRealtime(stocks.stream().map(StockDto::getSymbol).toList(), kafkaProducer::publish);
		log.info("시세 서비스 시작 - 모드: {}, 종목: {}", provider.mode(), names.keySet());
	}

	@PreDestroy
	public void stop() {
		provider.stop();
	}

	//시간이 지난 봉을 완성으로 넘긴다 (체결이 없는 구간에도 봉을 닫기 위해)
	@Scheduled(fixedDelay = 1_000)
	public void sealCandles() {
		sealExpired(System.currentTimeMillis() / 1000);
	}

	public QuoteDto getQuote(String symbol) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		QuoteDto quote = latest.get(symbol);
		boolean stale = quote == null || System.currentTimeMillis() - receivedMillis(quote) > STALE_MS;
		if (stale) {
			try {
				quote = refreshFromRest(symbol);
			} catch (MarketDataException e) {
				if (quote == null) throw e;
				log.warn("현재가 보충 실패, 마지막 값 사용 ({}): {}", symbol, e.getMessage());
			}
		}
		return quote;
	}

	//WebSocket 첫 구독 시 바로 보내줄 스냅샷 (없으면 null)
	public QuoteDto getLatest(String symbol) {
		return latest.get(symbol);
	}

	public boolean isSupported(String symbol) {
		return names.containsKey(symbol);
	}

	public MarketStatusDto getStatus() {
		return new MarketStatusDto(
				provider.mode(),
				feedStatus(System.currentTimeMillis()),
				MarketHours.isOpenNow(),
				lastTickAt,
				System.currentTimeMillis());
	}

	//Kafka 확인 메시지 수신
	public void onKafkaHeartbeat() {
		lastKafkaHeartbeat = System.currentTimeMillis();
	}

	//Kafka 로 받은 체결. 중복·역순은 건너뛴다
	public void onTick(QuoteDto tick) {
		if (tick.getSymbol() == null || !names.containsKey(tick.getSymbol()) || !isNewTick(tick)) return;
		lastTickAt = System.currentTimeMillis();
		addToBars(tick);
		apply(tick);
	}

	//완성된 봉 + 진행 중인 봉 (오래된 순)
	public synchronized List<ChartPointDto> candles(String symbol, String interval) {
		Series series = series(symbol).get(interval);
		List<ChartPointDto> points = new ArrayList<>(series.completed);
		if (series.current != null) points.add(series.current.toDto(false));
		return points;
	}

	//진행 중인 봉 (없으면 마지막 완성 봉, 그것도 없으면 null)
	public synchronized ChartPointDto latestCandle(String symbol, String interval) {
		Series series = series(symbol).get(interval);
		if (series.current != null) return series.current.toDto(false);
		return series.completed.peekLast();
	}

	String feedStatus(long now) {
		if (now - lastKafkaHeartbeat > KAFKA_TIMEOUT_MS) return KAFKA_DOWN;
		return provider.feedStatus().name();
	}

	boolean isNewTick(QuoteDto tick) {
		if (tick.getEventTime() == null) return true;
		long time = OffsetDateTime.parse(tick.getEventTime()).toEpochSecond();
		long volume = tick.getCumulativeVolume() == null ? Long.MAX_VALUE : tick.getCumulativeVolume();
		LastTick last = lastTicks.get(tick.getSymbol());
		if (last != null) {
			if (time < last.time()) return false;
			//같은 초에는 누적 거래량이 늘어난 체결만 새 체결이다. 거래량이 없으면 같은 초는 중복으로 본다
			if (time == last.time() && (volume <= last.volume() || volume == Long.MAX_VALUE)) return false;
		}
		lastTicks.put(tick.getSymbol(), new LastTick(time, volume == Long.MAX_VALUE ? 0 : volume));
		return true;
	}

	synchronized void sealExpired(long nowEpochSeconds) {
		bars.values().forEach(map -> map.values().forEach(series -> {
			if (series.current != null && series.current.start + series.seconds + SEAL_GRACE_SECONDS <= nowEpochSeconds) {
				seal(series);
			}
		}));
	}

	private synchronized void addToBars(QuoteDto tick) {
		if (tick.getPrice() == null || tick.getEventTime() == null) return;
		long time = OffsetDateTime.parse(tick.getEventTime()).toEpochSecond();
		long delta = volumeDelta(tick);
		for (Series series : series(tick.getSymbol()).values()) {
			long start = time - Math.floorMod(time, (long) series.seconds);
			if (start <= series.lastSealedStart || (series.current != null && start < series.current.start)) continue;	//이미 지난 구간
			if (series.current != null && start > series.current.start) seal(series);
			if (series.current == null) series.current = new Bar(start, tick.getPrice());
			Bar bar = series.current;
			bar.high = bar.high.max(tick.getPrice());
			bar.low = bar.low.min(tick.getPrice());
			bar.close = tick.getPrice();
			bar.volume += delta;
		}
	}

	private void seal(Series series) {
		Bar bar = series.current;
		series.current = null;
		series.lastSealedStart = bar.start;
		series.completed.addLast(bar.toDto(true));
		while (series.completed.size() > MAX_BARS) series.completed.removeFirst();
	}

	private long volumeDelta(QuoteDto tick) {
		Long volume = tick.getCumulativeVolume();
		if (volume == null) return 0;
		Long last = lastVolume.put(tick.getSymbol(), volume);
		if (last == null) return 0;	//연결 후 첫 체결은 체결량을 알 수 없다
		return volume >= last ? volume - last : volume;
	}

	private Map<String, Series> series(String symbol) {
		return bars.computeIfAbsent(symbol, key -> {
			Map<String, Series> map = new LinkedHashMap<>();
			INTERVALS.forEach((code, seconds) -> map.put(code, new Series(seconds)));
			return map;
		});
	}

	//외부 호출 수 제한 보호: 최근에 조회했으면 마지막 값을, 최근에 실패했으면 같은 오류를 바로 돌려준다
	private QuoteDto refreshFromRest(String symbol) {
		long now = System.currentTimeMillis();
		Long last = lastRestFetch.get(symbol);
		QuoteDto current = latest.get(symbol);
		if (last != null && now - last < REST_MIN_GAP_MS && current != null) {
			return current;
		}
		Long failedAt = lastRestFailure.get(symbol);
		if (failedAt != null && now - failedAt < REST_FAILURE_GAP_MS) {
			if (current != null) return current;
			throw new MarketDataException("최근 시세 조회에 실패한 종목입니다: " + symbol);
		}
		lastRestFetch.put(symbol, now);
		try {
			QuoteDto fetched = provider.fetchQuote(symbol);
			lastRestFailure.remove(symbol);
			return apply(fetched);
		} catch (MarketDataException e) {
			lastRestFailure.put(symbol, now);
			throw e;
		}
	}

	private QuoteDto apply(QuoteDto incoming) {
		String symbol = incoming.getSymbol();
		QuoteDto previous = latest.get(symbol);
		QuoteDto merged = incoming.toBuilder()
				.eventId(incoming.getEventId() != null ? incoming.getEventId() : symbol + "-" + eventSeq.incrementAndGet())
				.name(names.getOrDefault(symbol, symbol))
				.market(markets.get(symbol))
				.previousClose(incoming.getPreviousClose() != null ? incoming.getPreviousClose()
						: previous != null ? previous.getPreviousClose() : null)
				.receivedAt(MarketHours.now().truncatedTo(ChronoUnit.MILLIS).toOffsetDateTime().toString())
				.dataMode(provider.mode())
				.build();
		latest.put(symbol, merged);
		publisher.publishEvent(new QuoteUpdatedEvent(merged));
		return merged;
	}

	//receivedAt(ISO 시각)을 epoch ms 로 (오래된 시세 판단용)
	private static long receivedMillis(QuoteDto quote) {
		return OffsetDateTime.parse(quote.getReceivedAt()).toInstant().toEpochMilli();
	}

	static String isoTime(long epochSeconds) {
		return Instant.ofEpochSecond(epochSeconds).atZone(MarketHours.KST).toOffsetDateTime().toString();
	}
}
