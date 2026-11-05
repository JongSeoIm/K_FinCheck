package com.example.stock_api.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.stock_api.realtime.QuoteStore;

import lombok.RequiredArgsConstructor;

/**
 * 브라우저용 실시간 시세 WebSocket (ws://localhost:8080/ws/stock).
 * 연결되면 상태와 지원 종목의 최신 시세를 먼저 보내고, 이후 체결이 올 때마다 push 합니다.
 */
@Component
@RequiredArgsConstructor
public class StockWebSocketHandler extends TextWebSocketHandler {

    private final QuoteStore hub;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 여러 스레드가 동시에 보내도 안전하도록 감쌉니다 (전송 5초 · 버퍼 512KB 제한)
        hub.register(new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 브라우저 → 서버 메시지는 쓰지 않습니다. (연결 확인은 서버의 15초 status 하트비트로 합니다)
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.unregister(session.getId());
    }
}
