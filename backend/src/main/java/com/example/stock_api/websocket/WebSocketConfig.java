package com.example.stock_api.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final StockWebSocketHandler stockWebSocketHandler;

    @Value("${app.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 프런트엔드(localhost:3000)가 ws://localhost:8080/ws/stock 으로 접속
        registry.addHandler(stockWebSocketHandler, "/ws/stock").setAllowedOrigins(allowedOrigins);
    }
}
