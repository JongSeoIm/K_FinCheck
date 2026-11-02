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
        // 프론트엔드가 ws://localhost:8080/ws/stock 으로 접속할 수 있게 열어둠
        registry.addHandler(stockWebSocketHandler, "/ws/stock").setAllowedOrigins("*");
    }
}