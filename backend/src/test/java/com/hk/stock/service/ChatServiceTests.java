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
import com.hk.stock.dtos.ChatConversationDto;
import com.hk.stock.dtos.StockDto;

//AI 서버 호출 실패 처리 (DB 없이 실행, 아무도 듣지 않는 포트로 호출)
class ChatServiceTests {

	@Test
	void 받은_답변만_대화_기록에_남긴다() {
		org.junit.jupiter.api.Assertions.assertTrue(ChatService.isRemembered("answered"));
		org.junit.jupiter.api.Assertions.assertTrue(ChatService.isRemembered("need_more_info"));
		org.junit.jupiter.api.Assertions.assertTrue(ChatService.isRemembered("out_of_scope"));
		org.junit.jupiter.api.Assertions.assertTrue(ChatService.isRemembered("unavailable"));	//자료 없음·조회 실패 안내
		org.junit.jupiter.api.Assertions.assertFalse(ChatService.isRemembered("error"));	//답변을 받지 못함
	}

	@Test
	void AI_서버에_연결하지_못하면_503과_안내_문구를_돌려준다() {
		AiProperties props = new AiProperties();
		props.setServerUrl("http://127.0.0.1:1");
		StockService stockService = mock(StockService.class);
		when(stockService.getStock(anyString())).thenReturn(mock(StockDto.class));
		ChatHistoryStore history = mock(ChatHistoryStore.class);
		when(history.room(1L, "room")).thenReturn(new ChatConversationDto("room", "005930", "삼성전자", "새 대화", null, null));
		ChatService chatService = new ChatService(props, stockService, history, new ObjectMapper(), RestClient.builder());

		ChatService.AiServerException e = assertThrows(ChatService.AiServerException.class,
				() -> chatService.ask(1L, "005930", "room", "삼성전자 현재가 알려줘"));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
		assertEquals("AI 서버에 연결할 수 없습니다.", e.getMessage());
	}
}
