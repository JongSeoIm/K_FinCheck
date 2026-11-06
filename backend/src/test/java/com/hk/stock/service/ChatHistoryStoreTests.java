package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.ChatConversationDto;
import com.hk.stock.dtos.ChatMessageDto;
import com.hk.stock.dtos.ChatMessageRowDto;
import com.hk.stock.mapper.ChatMapper;
import com.hk.stock.service.ChatHistoryStore.ConversationNotFoundException;

class ChatHistoryStoreTests {

	private final ChatMapper mapper = mock(ChatMapper.class);
	private final ChatHistoryStore store = new ChatHistoryStore(mapper, new ObjectMapper(), TransactionOperations.withoutTransaction());

	private static final ChatMessageDto QUESTION = new ChatMessageDto("user", "삼성전자 현재가", null, List.of(), List.of(), "now");
	private static final ChatMessageDto ANSWER = new ChatMessageDto("assistant", "22만 원", "answered",
			List.of(Map.of("provider", "KIS", "label", "삼성전자 현재가")), List.of(Map.of("type", "quote")), "now");

	private static ChatConversationDto room() {
		return new ChatConversationDto("room", "005930", "삼성전자", "제목", null, null);
	}

	private static ChatConversationDto newRoom() {
		return new ChatConversationDto("room", "005930", "삼성전자", "새 대화", null, null);
	}

	@Test
	void 대화방을_먼저_만들면_기본_제목을_돌려준다() {
		when(mapper.getConversation(eq(1L), anyString())).thenReturn(newRoom());

		assertEquals("새 대화", store.create(1L, "005930", "삼성전자").getTitle());

		verify(mapper).insertConversation(anyString(), eq(1L), eq("005930"), eq("삼성전자"));
	}

	@Test
	void 첫_답변을_저장하면_제목을_첫_질문으로_바꾸고_메시지를_저장한다() {
		when(mapper.getConversation(1L, "room")).thenReturn(newRoom());

		store.append(1L, "room", QUESTION, ANSWER);

		verify(mapper).touchConversation("room", "삼성전자 현재가");
		ArgumentCaptor<ChatMessageRowDto> rows = ArgumentCaptor.forClass(ChatMessageRowDto.class);
		verify(mapper, org.mockito.Mockito.times(2)).insertMessage(rows.capture());
		assertNull(rows.getAllValues().get(0).getResponseJson());
		assertEquals("삼성전자 현재가", rows.getAllValues().get(0).getContextContent());
		assertEquals("22만 원", rows.getAllValues().get(1).getContextContent());
		String json = rows.getAllValues().get(1).getResponseJson();
		assertTrue(json.contains("\"status\":\"answered\""));
		assertTrue(json.contains("\"provider\":\"KIS\""));
		assertTrue(json.contains("\"visuals\":[{\"type\":\"quote\"}]"));
	}

	@Test
	void 이어서_저장하면_제목은_그대로_둔다() {
		when(mapper.getConversation(1L, "room")).thenReturn(room());

		store.append(1L, "room", QUESTION, ANSWER);

		verify(mapper).touchConversation("room", "제목");
	}

	@Test
	void 답변을_기다리는_사이_지워진_대화방에는_저장하지_않는다() {
		when(mapper.getConversation(1L, "room")).thenReturn(null);

		assertThrows(ConversationNotFoundException.class,
				() -> store.append(1L, "room", QUESTION, ANSWER));

		verify(mapper, never()).insertMessage(any());
	}

	@Test
	void 제목과_문맥은_컬럼_길이에_맞춰_자른다() {
		String longText = "가".repeat(20_000);

		when(mapper.getConversation(1L, "room")).thenReturn(newRoom());

		store.append(1L, "room", new ChatMessageDto("user", longText, null, List.of(), List.of(), "now"), ANSWER);

		ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
		verify(mapper).touchConversation(eq("room"), title.capture());
		assertEquals(80, title.getValue().length());
		ArgumentCaptor<ChatMessageRowDto> rows = ArgumentCaptor.forClass(ChatMessageRowDto.class);
		verify(mapper, org.mockito.Mockito.times(2)).insertMessage(rows.capture());
		assertEquals(20_000, rows.getAllValues().get(0).getContent().length());
		assertEquals(16_000, rows.getAllValues().get(0).getContextContent().length());
	}

	@Test
	void 다른_회원의_대화방은_찾을_수_없고_지울_수도_없다() {
		when(mapper.getConversation(2L, "room")).thenReturn(null);
		when(mapper.deleteConversation(2L, "room")).thenReturn(0);

		assertThrows(ConversationNotFoundException.class, () -> store.room(2L, "room"));
		assertThrows(ConversationNotFoundException.class, () -> store.delete(2L, "room"));
	}

	@Test
	void 문맥은_최근_10개_복원은_100개까지_읽고_출처를_되살린다() {
		ChatMessageRowDto row = new ChatMessageRowDto("room", "assistant", "22만 원", "AI용 문맥",
				"{\"status\":\"answered\",\"sources\":[{\"provider\":\"KIS\"}],\"visuals\":[{\"type\":\"quote\"}]}", null);
		when(mapper.getRecentMessages("room", 10)).thenReturn(List.of(row));
		when(mapper.getRecentMessages("room", 100)).thenReturn(List.of(row, row));

		List<ChatMessageDto> context = store.context("room");
		assertEquals(1, context.size());
		assertEquals("AI용 문맥", context.get(0).content());
		List<ChatMessageDto> restored = store.restore("room");
		assertEquals(2, restored.size());
		assertEquals("22만 원", restored.get(0).content());
		assertEquals("answered", restored.get(0).status());
		assertEquals("KIS", restored.get(0).sources().get(0).get("provider"));
		assertEquals("quote", restored.get(0).visuals().get(0).get("type"));
	}

	@Test
	void 대화방_목록은_최대_50개를_읽는다() {
		when(mapper.getConversations(1L, "005930", 50)).thenReturn(List.of(room()));

		assertEquals("room", store.rooms(1L, "005930").get(0).getConversationId());
		verify(mapper, never()).insertConversation(anyString(), any(), any(), any());
	}
}
