package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.config.AiProperties;
import com.hk.stock.dtos.StockDto;

//AI 서버 호출 실패 처리 (DB 없이 실행, 아무도 듣지 않는 포트로 호출)
class ChatServiceTests {

	@Test
	void AI_서버에_연결하지_못하면_503과_안내_문구를_돌려준다() {
		AiProperties props = new AiProperties();
		props.setServerUrl("http://127.0.0.1:1");
		StockService stockService = mock(StockService.class);
		when(stockService.getStock(anyString())).thenReturn(mock(StockDto.class));
		ChatService chatService = new ChatService(props, stockService, new ChatMemoryStore(), new ObjectMapper(), RestClient.builder());

		ChatService.AiServerException e = assertThrows(ChatService.AiServerException.class,
				() -> chatService.ask(1L, "005930", "삼성전자 현재가 알려줘"));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
		assertEquals("AI 서버에 연결할 수 없습니다.", e.getMessage());
	}
}
