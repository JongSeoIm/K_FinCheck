package com.hk.stock.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.MarketStatusDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.dtos.StockDto;
import com.hk.stock.market.MarketDataException;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;

import jakarta.annotation.PreDestroy;

/**
 * 종목별 최신 시세를 서버 메모리에 보관한다.
 * - Polling(REST)과 Push(WebSocket)는 모두 이 최신 상태를 사용한다.
 * - 실시간 체결이 오면 갱신하고 QuoteUpdatedEvent 를 발행한다.
 * - 실시간 체결이 한동안 없으면(장외·연결 끊김) REST 현재가로 보충한다.
 */
@Service
public class QuoteService {

	private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
	private static final long STALE_MS = 60_000;		//이 시간 동안 갱신이 없으면 REST로 보충
	private static final long REST_MIN_GAP_MS = 5_000;	//종목별 REST 보충 최소 간격
	private static final long REST_FAILURE_GAP_MS = 60_000;	//조회 실패한 종목은 1분 뒤에 다시 시도 (권한 없는 종목 등)

	@Autowired
	private MarketDataProvider provider;
	@Autowired
	private StockService stockService;
	@Autowired
	private ApplicationEventPublisher publisher;

	private final Map<String, QuoteDto> latest = new ConcurrentHashMap<>();
	private final Map<String, String> names = new ConcurrentHashMap<>();
	private final Map<String, Long> lastRestFetch = new ConcurrentHashMap<>();
	private final Map<String, Long> lastRestFailure = new ConcurrentHashMap<>();
	private volatile Long lastTickAt;

	//서버 준비가 끝나면 지원 종목의 현재가를 채우고 실시간 수신을 시작한다
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		List<StockDto> stocks = stockService.getAllStocks();
		stocks.forEach(stock -> names.put(stock.getSymbol(), stock.getName()));
		for (StockDto stock : stocks) {
			try {
				refreshFromRest(stock.getSymbol());
			} catch (MarketDataException e) {
				log.warn("초기 현재가 조회 실패 ({}): {}", stock.getSymbol(), e.getMessage());
			}
		}
		provider.startRealtime(stocks.stream().map(StockDto::getSymbol).toList(), this::onTick);
		log.info("시세 서비스 시작 - 모드: {}, 종목: {}", provider.mode(), names.keySet());
	}

	@PreDestroy
	public void stop() {
		provider.stop();
	}

	public QuoteDto getQuote(String symbol) {
		stockService.getStock(symbol);	//지원 종목인지 확인
		QuoteDto quote = latest.get(symbol);
		boolean stale = quote == null || System.currentTimeMillis() - quote.getUpdatedAt() > STALE_MS;
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
				provider.feedStatus().name(),
				MarketHours.isOpenNow(),
				lastTickAt,
				System.currentTimeMillis());
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

	//실시간 체결 수신 (제공처 스레드에서 호출됨)
	private void onTick(QuoteDto tick) {
		lastTickAt = System.currentTimeMillis();
		apply(tick);
	}

	private QuoteDto apply(QuoteDto incoming) {
		String symbol = incoming.getSymbol();
		QuoteDto previous = latest.get(symbol);
		QuoteDto merged = incoming.toBuilder()
				.name(names.getOrDefault(symbol, symbol))
				.previousClose(incoming.getPreviousClose() != null ? incoming.getPreviousClose()
						: previous != null ? previous.getPreviousClose() : null)
				.updatedAt(System.currentTimeMillis())
				.build();
		latest.put(symbol, merged);
		publisher.publishEvent(new QuoteUpdatedEvent(merged));
		return merged;
	}
}
