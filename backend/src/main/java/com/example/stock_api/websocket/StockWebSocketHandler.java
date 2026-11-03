package com.example.stock_api.websocket;

import org.springframework.stereotype.Component; // 이 임포트가 필요합니다
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component // <-- 이 어노테이션이 꼭 있어야 합니다!
public class StockWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("웹소켓 연결 성공: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        System.out.println("수신된 메시지: " + message.getPayload());
    }
}