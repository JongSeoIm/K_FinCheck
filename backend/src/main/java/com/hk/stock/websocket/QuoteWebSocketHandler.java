package com.hk.stock.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.service.QuoteStore;
import com.hk.stock.service.QuoteUpdatedEvent;

/**
 * 브라우저 실시간 시세 Push: 0.5초 간격으로 바뀐 최신 상태를 보낸다 (체결마다 보내지 않는다)
 * 브라우저 → 서버
 *   {"type":"subscribe","symbols":["005930"]}    구독 추가 (바로 최신 시세 스냅샷을 보내줌)
 *   {"type":"unsubscribe","symbols":["005930"]}  구독 해제
 *   {"type":"chart","symbol":"005930","interval":"1m"}  차트 봉 구독 (interval 이 없으면 해제, 초·분봉만)
 * 서버 → 브라우저
 *   {"type":"quote","data":{...QuoteDto}}
 *   {"type":"candle","symbol":"005930","interval":"1m","data":{...ChartPointDto}}
 *   {"type":"error","message":"..."}
 */
@Component
public class QuoteWebSocketHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(QuoteWebSocketHandler.class);
	private static final long PUSH_INTERVAL_MS = 500;
	private static final int SEND_TIME_LIMIT_MS = 5_000;
	private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

	@Autowired
	private QuoteStore quoteStore;
	@Autowired
	private ObjectMapper objectMapper;

	//세션별 구독 종목. 느린 브라우저가 다른 브라우저 전송을 막지 않도록 세션을 Decorator로 감싼다
	private final Map<String, Client> clients = new ConcurrentHashMap<>();
	//다음 전송 때 보낼 종목별 최신 시세
	private final Map<String, QuoteDto> pending = new ConcurrentHashMap<>();

	private record ChartSubscription(String symbol, String interval) {
	}

	private static final class Client {
		final WebSocketSession session;
		final Set<String> symbols = ConcurrentHashMap.newKeySet();
		volatile ChartSubscription chart;
		ChartPointDto lastCandle;

		Client(WebSocketSession session) {
			this.session = session;
		}

		WebSocketSession session() {
			return session;
		}

		Set<String> symbols() {
			return symbols;
		}
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
		clients.put(session.getId(), new Client(safe));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
		Client client = clients.get(session.getId());
		if (client == null) return;

		JsonNode json;
		try {
			json = objectMapper.readTree(message.getPayload());
		} catch (IOException e) {
			sendError(client, "JSON 형식이 아닙니다.");
			return;
		}

		String type = json.path("type").asText();
		if ("chart".equals(type)) {
			subscribeChart(client, json);
			return;
		}
		for (JsonNode node : json.path("symbols")) {
			String symbol = node.asText();
			if (!quoteStore.isSupported(symbol)) {
				sendError(client, "지원하지 않는 종목입니다: " + symbol);
				continue;
			}
			if ("subscribe".equals(type)) {
				client.symbols().add(symbol);
				QuoteDto snapshot = quoteStore.getLatest(symbol);
				if (snapshot != null) sendQuote(client, snapshot);
			} else if ("unsubscribe".equals(type)) {
				client.symbols().remove(symbol);
			}
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		clients.remove(session.getId());
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		clients.remove(session.getId());
	}

	private void subscribeChart(Client client, JsonNode json) {
		String symbol = json.path("symbol").asText();
		String interval = json.path("interval").asText("");
		client.lastCandle = null;
		if (interval.isEmpty() || !QuoteStore.INTERVALS.containsKey(interval) || !quoteStore.isSupported(symbol)) {
			client.chart = null;
			return;
		}
		client.chart = new ChartSubscription(symbol, interval);
	}

	//최신 시세가 바뀌면 표시만 해 두고, 0.5초마다 한 번씩 보낸다
	@EventListener
	public void onQuoteUpdated(QuoteUpdatedEvent event) {
		QuoteDto quote = event.quote();
		pending.put(quote.getSymbol(), quote);
	}

	@Scheduled(fixedRate = PUSH_INTERVAL_MS)
	public void flush() {
		for (String symbol : pending.keySet()) {
			QuoteDto quote = pending.remove(symbol);
			if (quote == null) continue;
			for (Client client : clients.values()) {
				if (client.symbols().contains(symbol)) sendQuote(client, quote);
			}
		}
		for (Client client : clients.values()) {
			ChartSubscription chart = client.chart;
			if (chart == null) continue;
			ChartPointDto candle = quoteStore.latestCandle(chart.symbol(), chart.interval());
			if (candle == null || candle.equals(client.lastCandle)) continue;
			client.lastCandle = candle;
			sendCandle(client, chart, candle);
		}
	}

	private void sendCandle(Client client, ChartSubscription chart, ChartPointDto candle) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "candle");
		node.put("symbol", chart.symbol());
		node.put("interval", chart.interval());
		node.set("data", objectMapper.valueToTree(candle));
		send(client, node.toString());
	}

	private void sendQuote(Client client, QuoteDto quote) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "quote");
		node.set("data", objectMapper.valueToTree(quote));
		send(client, node.toString());
	}

	private void sendError(Client client, String message) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "error");
		node.put("message", message);
		send(client, node.toString());
	}

	private void send(Client client, String text) {
		WebSocketSession session = client.session();
		if (!session.isOpen()) {
			clients.remove(session.getId());
			return;
		}
		try {
			session.sendMessage(new TextMessage(text));
		} catch (Exception e) {
			log.debug("WebSocket 전송 실패, 연결 정리: {}", e.getMessage());
			clients.remove(session.getId());
			try {
				session.close(CloseStatus.SESSION_NOT_RELIABLE);
			} catch (IOException ignored) {
			}
		}
	}
}
