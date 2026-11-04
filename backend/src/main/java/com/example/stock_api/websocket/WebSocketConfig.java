package com.example.stock_api.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final StockWebSocketHandler stockWebSocketHandler;

    public WebSocketConfig(StockWebSocketHandler stockWebSocketHandler) {
        this.stockWebSocketHandler = stockWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 화면(localhost:3000)이 ws://localhost:8080/ws/stock 으로 직접 접속합니다.
        registry.addHandler(stockWebSocketHandler, "/ws/stock")
                .setAllowedOrigins("http://localhost:3000", "http://127.0.0.1:3000");
    }
}
