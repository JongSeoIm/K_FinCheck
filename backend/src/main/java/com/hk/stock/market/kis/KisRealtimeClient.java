package com.hk.stock.market.kis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hk.stock.config.KisProperties;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.market.MarketDataProvider.FeedStatus;

/**
 * KIS 실시간 체결가(H0STCNT0) WebSocket 수신
 * - 서버 1곳에서만 KIS에 접속하고, 브라우저에는 우리 서버 WebSocket(/ws/quotes)으로 전달한다.
 * - 수신 메시지
 *   · 체결 데이터: "0|H0STCNT0|001|005930^093001^71000^2^500^0.71^..." (^ 구분, 여러 건이면 이어 붙여 옴)
 *   · 제어 메시지(JSON): 구독 응답, PINGPONG(받은 그대로 돌려보내야 연결 유지)
 * - 연결이 끊기면 5초부터 최대 60초 간격으로 재접속하고 종목을 다시 구독한다.
 */
public class KisRealtimeClient {

	private static final Logger log = LoggerFactory.getLogger(KisRealtimeClient.class);
	private static final String TR_ID = "H0STCNT0";

	private final KisProperties props;
	private final KisAuthService auth;
	private final ObjectMapper objectMapper;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "kis-realtime");
		t.setDaemon(true);
		return t;
	});

	private List<String> symbols = List.of();
	private Consumer<QuoteDto> onTick;
	private volatile WebSocket webSocket;
	private volatile FeedStatus status = FeedStatus.DISCONNECTED;
	private volatile boolean stopped = false;
	private int retryCount = 0;
	private ScheduledFuture<?> pendingReconnect;

	public KisRealtimeClient(KisProperties props, KisAuthService auth, ObjectMapper objectMapper) {
		this.props = props;
		this.auth = auth;
		this.objectMapper = objectMapper;
	}

	public FeedStatus status() {
		return status;
	}

	public void start(List<String> symbols, Consumer<QuoteDto> onTick) {
		this.symbols = List.copyOf(symbols);
		this.onTick = onTick;
		this.stopped = false;
		scheduler.execute(() -> connect(false));
	}

	public void stop() {
		stopped = true;
		WebSocket ws = webSocket;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "server shutdown");
		}
		scheduler.shutdownNow();
	}

	private void connect(boolean renewApprovalKey) {
		if (stopped) return;
		status = FeedStatus.CONNECTING;
		try {
			String approvalKey = auth.getApprovalKey(renewApprovalKey);
			webSocket = HttpClient.newHttpClient()
					.newWebSocketBuilder()
					.connectTimeout(Duration.ofSeconds(10))
					.buildAsync(URI.create(props.getWsUrl()), new Listener())
					.join();
			for (String symbol : symbols) {
				webSocket.sendText(subscribeMessage(approvalKey, symbol), true).join();
			}
			status = FeedStatus.CONNECTED;
			retryCount = 0;
			log.info("KIS 실시간 연결 완료, 구독 종목: {}", symbols);
		} catch (Exception e) {
			log.warn("KIS 실시간 연결 실패: {}", e.getMessage());
			scheduleReconnect(true);
		}
	}

	//onClose·onError가 연달아 와도 재연결은 한 번만 예약한다
	private synchronized void scheduleReconnect(boolean renewApprovalKey) {
		status = FeedStatus.DISCONNECTED;
		if (stopped || scheduler.isShutdown()) return;
		if (pendingReconnect != null && !pendingReconnect.isDone()) return;
		long delay = Math.min(60, 5L * (1L << Math.min(retryCount, 4)));
		retryCount++;
		log.info("{}초 후 KIS 실시간 재연결 시도", delay);
		pendingReconnect = scheduler.schedule(() -> connect(renewApprovalKey), delay, TimeUnit.SECONDS);
	}

	private String subscribeMessage(String approvalKey, String symbol) {
		ObjectNode root = objectMapper.createObjectNode();
		ObjectNode header = root.putObject("header");
		header.put("approval_key", approvalKey);
		header.put("custtype", "P");
		header.put("tr_type", "1");	//1: 등록, 2: 해제
		header.put("content-type", "utf-8");
		ObjectNode input = root.putObject("body").putObject("input");
		input.put("tr_id", TR_ID);
		input.put("tr_key", symbol);
		return root.toString();
	}

	private void handleMessage(WebSocket ws, String message) {
		char first = message.isEmpty() ? ' ' : message.charAt(0);
		if (first == '0' || first == '1') {
			handleData(message);
			return;
		}
		try {
			JsonNode json = objectMapper.readTree(message);
			String trId = json.path("header").path("tr_id").asText();
			if ("PINGPONG".equals(trId)) {
				ws.sendText(message, true);	//받은 그대로 응답
				return;
			}
			JsonNode body = json.path("body");
			if (!body.isMissingNode() && !"0".equals(body.path("rt_cd").asText("0"))) {
				log.warn("KIS 실시간 응답 오류: {} {}", body.path("msg_cd").asText(), body.path("msg1").asText());
			} else {
				log.debug("KIS 실시간 응답: {} {}", trId, body.path("msg1").asText());
			}
		} catch (Exception e) {
			log.debug("알 수 없는 KIS 실시간 메시지: {}", message);
		}
	}

	//"0|H0STCNT0|003|...^...^..." → 체결 1건씩 QuoteDto로 변환
	private void handleData(String message) {
		String[] parts = message.split("\\|", 4);
		if (parts.length < 4 || !TR_ID.equals(parts[1])) return;
		int count = KisParsers.toInt(parts[2], 1);
		String[] fields = parts[3].split("\\^");
		int size = fields.length / Math.max(count, 1);
		for (int i = 0; i < count; i++) {
			int base = i * size;
			if (base + 13 >= fields.length) break;
			try {
				onTick.accept(KisParsers.realtimeTick(fields, base));
			} catch (Exception e) {
				log.warn("실시간 체결 변환 실패: {}", e.getMessage());
			}
		}
	}

	private class Listener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket ws) {
			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String message = buffer.toString();
				buffer.setLength(0);
				handleMessage(ws, message);
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			log.warn("KIS 실시간 연결 종료: {} {}", statusCode, reason);
			scheduleReconnect(false);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			log.warn("KIS 실시간 연결 오류: {}", error.getMessage());
			scheduleReconnect(true);
		}
	}
}
