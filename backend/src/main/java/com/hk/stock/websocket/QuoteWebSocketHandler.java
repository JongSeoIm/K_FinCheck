package com.hk.stock.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.service.QuoteService;
import com.hk.stock.service.QuoteUpdatedEvent;

/**
 * 브라우저 실시간 시세 Push
 * 브라우저 → 서버
 *   {"type":"subscribe","symbols":["005930"]}    구독 추가 (바로 최신 시세 스냅샷을 보내줌)
 *   {"type":"unsubscribe","symbols":["005930"]}  구독 해제
 * 서버 → 브라우저
 *   {"type":"quote","data":{...QuoteDto}}
 *   {"type":"error","message":"..."}
 */
@Component
public class QuoteWebSocketHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(QuoteWebSocketHandler.class);
	private static final int SEND_TIME_LIMIT_MS = 5_000;
	private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

	@Autowired
	private QuoteService quoteService;
	@Autowired
	private ObjectMapper objectMapper;

	//세션별 구독 종목. 느린 브라우저가 다른 브라우저 전송을 막지 않도록 세션을 Decorator로 감싼다
	private final Map<String, Client> clients = new ConcurrentHashMap<>();

	private record Client(WebSocketSession session, Set<String> symbols) {
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
		clients.put(session.getId(), new Client(safe, ConcurrentHashMap.newKeySet()));
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
		for (JsonNode node : json.path("symbols")) {
			String symbol = node.asText();
			if (!quoteService.isSupported(symbol)) {
				sendError(client, "지원하지 않는 종목입니다: " + symbol);
				continue;
			}
			if ("subscribe".equals(type)) {
				client.symbols().add(symbol);
				QuoteDto snapshot = quoteService.getLatest(symbol);
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

	//최신 시세가 바뀌면 그 종목을 구독한 브라우저에만 전송
	@EventListener
	public void onQuoteUpdated(QuoteUpdatedEvent event) {
		QuoteDto quote = event.quote();
		for (Client client : clients.values()) {
			if (client.symbols().contains(quote.getSymbol())) {
				sendQuote(client, quote);
			}
		}
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
