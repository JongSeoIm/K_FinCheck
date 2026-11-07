package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.dtos.StockDto;
import com.hk.stock.market.MarketDataException;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.QuoteKafkaProducer;

class QuoteStoreTests {

	private final MarketDataProvider provider = mock(MarketDataProvider.class);
	private final StockService stockService = mock(StockService.class);
	private final QuoteKafkaProducer producer = mock(QuoteKafkaProducer.class);
	private final QuoteStore store = new QuoteStore(provider, stockService, mock(ApplicationEventPublisher.class), producer);

	@BeforeEach
	void setUp() {
		StockDto samsung = mock(StockDto.class);
		when(samsung.getSymbol()).thenReturn("005930");
		when(samsung.getName()).thenReturn("삼성전자");
		when(stockService.getAllStocks()).thenReturn(List.of(samsung));
		when(provider.fetchQuote(anyString())).thenThrow(new MarketDataException("테스트"));
		when(provider.mode()).thenReturn("KIS");
		store.start();
	}

	private static QuoteDto quote(String time, long price, Long volume) {
		return QuoteDto.builder().symbol("005930").price(BigDecimal.valueOf(price))
				.eventTime(time).cumulativeVolume(volume).source("KIS").build();
	}

	private void tick(String time, long price, long volume) {
		store.onTick(quote("2026-11-05T" + time + "+09:00", price, volume));
	}

	private static void assertBar(ChartPointDto bar, long open, long high, long low, long close) {
		assertEquals(0, BigDecimal.valueOf(open).compareTo(bar.getOpen()));
		assertEquals(0, BigDecimal.valueOf(high).compareTo(bar.getHigh()));
		assertEquals(0, BigDecimal.valueOf(low).compareTo(bar.getLow()));
		assertEquals(0, BigDecimal.valueOf(close).compareTo(bar.getClose()));
	}

	@Test
	void 받은_체결을_Kafka_발행으로_넘긴다() {
		verify(provider).startRealtime(org.mockito.ArgumentMatchers.eq(List.of("005930")), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void 소비한_체결로_최신_시세를_갱신한다() {
		tick("09:00:00", 10_000, 100);
		assertEquals(0, BigDecimal.valueOf(10_000).compareTo(store.getLatest("005930").getPrice()));
		assertEquals("삼성전자", store.getLatest("005930").getName());
	}

	@Test
	void 지원하지_않는_종목은_반영하지_않는다() {
		store.onTick(QuoteDto.builder().symbol("999999").price(BigDecimal.ONE).eventTime("2026-11-05T09:00:00+09:00").build());
		assertEquals(null, store.getLatest("999999"));
	}

	@Test
	void 중복과_역순_체결은_걸러내고_새_체결만_통과시킨다() {
		assertTrue(store.isNewTick(quote("2026-11-05T09:30:01+09:00", 1, 100L)));
		assertTrue(store.isNewTick(quote("2026-11-05T09:30:01+09:00", 1, 110L)));	//같은 초의 새 체결
		assertFalse(store.isNewTick(quote("2026-11-05T09:30:01+09:00", 1, 110L)));	//같은 메시지 재처리
		assertFalse(store.isNewTick(quote("2026-11-05T09:30:00+09:00", 1, 90L)));	//역순
		assertTrue(store.isNewTick(quote("2026-11-05T09:30:02+09:00", 1, 120L)));
		assertTrue(store.isNewTick(quote("2026-11-06T09:00:00+09:00", 1, 5L)));	//다음 거래일은 누적이 줄어도 통과
	}

	@Test
	void 수신한_모든_체결로_OHLC와_거래량을_만든다() {
		tick("09:00:00", 10_000, 100);
		tick("09:00:20", 10_500, 150);	//주기적으로 저장한 값이면 놓칠 수 있는 고가
		tick("09:00:40", 9_800, 170);
		tick("09:00:59", 10_100, 200);

		ChartPointDto bar = store.latestCandle("005930", "1m");
		assertBar(bar, 10_000, 10_500, 9_800, 10_100);
		assertEquals(100L, bar.getVolume());	//첫 체결은 기준값
		assertFalse(bar.getComplete());
	}

	@Test
	void 구간이_바뀌면_앞_봉을_완성한다() {
		tick("09:00:10", 100, 10);
		tick("09:01:05", 110, 30);

		List<ChartPointDto> bars = store.candles("005930", "1m");
		assertEquals(2, bars.size());
		assertTrue(bars.get(0).getComplete());
		assertFalse(bars.get(1).getComplete());
		assertEquals(20L, bars.get(1).getVolume());
	}

	@Test
	void 체결이_없는_구간에는_봉을_만들지_않는다() {
		tick("09:00:10", 100, 10);
		tick("09:05:10", 110, 20);

		List<ChartPointDto> bars = store.candles("005930", "1m");
		assertEquals(2, bars.size());
		assertTrue(bars.get(1).getTime().contains("T09:05"));
	}

	@Test
	void 시간이_지나면_체결이_없어도_봉을_닫는다() {
		tick("09:00:10", 100, 10);
		long minuteEnd = OffsetDateTime.parse("2026-11-05T09:01:00+09:00").toEpochSecond();

		store.sealExpired(minuteEnd);	//늦게 오는 체결을 기다린다
		assertFalse(store.candles("005930", "1m").get(0).getComplete());

		store.sealExpired(minuteEnd + 5);
		assertTrue(store.candles("005930", "1m").get(0).getComplete());
		assertFalse(store.candles("005930", "5m").get(0).getComplete());	//5분봉은 아직 진행 중
	}

	@Test
	void 이미_닫힌_구간에_늦게_온_체결이_같은_시각의_봉을_또_만들지_않는다() {
		tick("09:00:10", 100, 10);
		store.sealExpired(OffsetDateTime.parse("2026-11-05T09:01:05+09:00").toEpochSecond());

		tick("09:00:50", 105, 20);

		assertEquals(1, store.candles("005930", "1m").size());
	}

	@Test
	void 전_주기를_체결_시각_구간으로_나눈다() {
		tick("09:07:32", 100, 10);
		assertTrue(store.latestCandle("005930", "1s").getTime().contains("T09:07:32"));
		assertTrue(store.latestCandle("005930", "5s").getTime().contains("T09:07:30"));
		assertTrue(store.latestCandle("005930", "30s").getTime().contains("T09:07:30"));
		assertTrue(store.latestCandle("005930", "3m").getTime().contains("T09:06"));
		assertTrue(store.latestCandle("005930", "5m").getTime().contains("T09:05"));
		assertTrue(store.latestCandle("005930", "10m").getTime().contains("T09:00"));
		assertTrue(store.latestCandle("005930", "15m").getTime().contains("T09:00"));
		assertTrue(store.latestCandle("005930", "60m").getTime().contains("T09:00"));
	}

	@Test
	void 누적_거래량이_줄면_새_거래일로_보고_다시_시작한다() {
		tick("09:00:10", 100, 1000);
		store.onTick(quote("2026-11-06T09:00:00+09:00", 100, 7L));
		assertEquals(7L, store.latestCandle("005930", "1m").getVolume());
	}

	@Test
	void 일분봉을_더_긴_봉으로_묶고_수신_봉과_합친다() {
		List<ChartPointDto> minutes = List.of(
				new ChartPointDto("2026-11-05T09:00+09:00", BigDecimal.valueOf(100), BigDecimal.valueOf(120), BigDecimal.valueOf(90), BigDecimal.valueOf(110), 5L),
				new ChartPointDto("2026-11-05T09:04+09:00", BigDecimal.valueOf(110), BigDecimal.valueOf(130), BigDecimal.valueOf(105), BigDecimal.valueOf(125), 6L),
				new ChartPointDto("2026-11-05T09:05+09:00", BigDecimal.valueOf(125), BigDecimal.valueOf(126), BigDecimal.valueOf(124), BigDecimal.valueOf(125), 7L));

		List<ChartPointDto> rolled = CandleRollup.rollup(minutes, 300);
		assertEquals(2, rolled.size());
		assertBar(rolled.get(0), 100, 130, 90, 125);
		assertEquals(11L, rolled.get(0).getVolume());

		ChartPointDto live = new ChartPointDto("2026-11-05T09:05+09:00", BigDecimal.valueOf(125), BigDecimal.valueOf(140), BigDecimal.valueOf(125), BigDecimal.valueOf(135), 3L, false);
		List<ChartPointDto> merged = CandleRollup.merge(rolled, List.of(live));
		assertEquals(2, merged.size());
		assertBar(merged.get(1), 125, 140, 124, 135);
		assertFalse(merged.get(1).getComplete());
	}

	@Test
	void Kafka_확인_메시지가_끊기면_KAFKA_DOWN_이다() {
		when(provider.feedStatus()).thenReturn(MarketDataProvider.FeedStatus.CONNECTED);
		long now = System.currentTimeMillis();

		assertEquals(QuoteStore.KAFKA_DOWN, store.feedStatus(now));
		store.onKafkaHeartbeat();
		assertEquals("CONNECTED", store.feedStatus(System.currentTimeMillis()));
		assertEquals(QuoteStore.KAFKA_DOWN, store.feedStatus(System.currentTimeMillis() + 16_000));
	}
}
