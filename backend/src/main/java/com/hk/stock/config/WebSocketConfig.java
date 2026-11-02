package com.hk.stock.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.hk.stock.websocket.QuoteWebSocketHandler;

//브라우저용 실시간 시세 WebSocket: ws://localhost:8085/ws/quotes
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	@Autowired
	private QuoteWebSocketHandler quoteWebSocketHandler;

	@Value("${app.allowed-origins}")
	private String[] allowedOrigins;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(quoteWebSocketHandler, "/ws/quotes")
				.setAllowedOrigins(allowedOrigins);
	}
}
