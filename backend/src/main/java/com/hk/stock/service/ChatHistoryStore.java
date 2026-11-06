package com.hk.stock.service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.ChatConversationDto;
import com.hk.stock.dtos.ChatMessageDto;
import com.hk.stock.dtos.ChatMessageRowDto;
import com.hk.stock.mapper.ChatMapper;
import com.hk.stock.market.MarketHours;

/**
 * 대화 기록 (chat_conversation · chat_message)
 * - 회원은 종목마다 대화방을 여러 개 둘 수 있다. 로그아웃하거나 서버를 재시작해도 남아 다시 로그인하면 복원된다.
 * - 대화방은 질문 전에 만들고, 제목은 첫 답변을 저장할 때 첫 질문으로 바꾼다.
 * - 화면에는 content 를 최근 100개까지 복원하고, AI 에게는 context_content 를 최근 10개만 문맥으로 보낸다.
 */
@Component
public class ChatHistoryStore {

	private static final Logger log = LoggerFactory.getLogger(ChatHistoryStore.class);
	public static final int CONTEXT_MESSAGES = 10;
	public static final int RESTORE_MESSAGES = 100;
	public static final int LIST_ROOMS = 50;
	private static final int TITLE_LENGTH = 80;
	private static final String NEW_ROOM_TITLE = "새 대화";	//schema.sql 의 title 기본값
	//context_content 는 TEXT(64KB). 한 글자가 최대 4바이트이므로 16,000자까지 저장한다
	private static final int CONTEXT_LENGTH = 16_000;

	private final ChatMapper mapper;
	private final ObjectMapper objectMapper;
	private final TransactionOperations transaction;

	public ChatHistoryStore(ChatMapper mapper, ObjectMapper objectMapper, TransactionOperations transaction) {
		this.mapper = mapper;
		this.objectMapper = objectMapper;
		this.transaction = transaction;
	}

	public List<ChatConversationDto> rooms(Long userId, String symbol) {
		return mapper.getConversations(userId, symbol, LIST_ROOMS);
	}

	//본인 대화방이 아니면 없는 대화방과 똑같이 404 로 답한다
	public ChatConversationDto room(Long userId, String conversationId) {
		ChatConversationDto room = mapper.getConversation(userId, conversationId);
		if (room == null) throw new ConversationNotFoundException();
		return room;
	}

	public List<ChatMessageDto> context(String conversationId) {
		List<ChatMessageDto> messages = new ArrayList<>();
		for (ChatMessageRowDto row : mapper.getRecentMessages(conversationId, CONTEXT_MESSAGES)) {
			messages.add(new ChatMessageDto(row.getRole(), row.getContextContent(), null, List.of(), List.of(), null));
		}
		return messages;
	}

	public List<ChatMessageDto> restore(String conversationId) {
		List<ChatMessageDto> messages = new ArrayList<>();
		for (ChatMessageRowDto row : mapper.getRecentMessages(conversationId, RESTORE_MESSAGES)) {
			messages.add(toMessage(row));
		}
		return messages;
	}

	public ChatConversationDto create(Long userId, String symbol, String stockName) {
		String id = UUID.randomUUID().toString();
		mapper.insertConversation(id, userId, symbol, stockName);
		return room(userId, id);
	}

	public void append(Long userId, String conversationId, ChatMessageDto question, ChatMessageDto answer) {
		transaction.executeWithoutResult(status -> {
			String title = room(userId, conversationId).getTitle();	//답변을 기다리는 동안 다른 창에서 지웠을 수 있다
			mapper.touchConversation(conversationId, NEW_ROOM_TITLE.equals(title) ? title(question.content()) : title);
			mapper.insertMessage(row(conversationId, question));
			mapper.insertMessage(row(conversationId, answer));
		});
	}

	public void delete(Long userId, String conversationId) {
		if (mapper.deleteConversation(userId, conversationId) == 0) throw new ConversationNotFoundException();
	}

	private ChatMessageRowDto row(String conversationId, ChatMessageDto message) {
		String json = null;
		if ("assistant".equals(message.role())) {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", message.status());
			response.put("sources", message.sources());
			response.put("visuals", message.visuals());
			try {
				json = objectMapper.writeValueAsString(response);
			} catch (JsonProcessingException e) {
				log.warn("AI 응답 변환 실패: {}", e.getMessage());
			}
		}
		return new ChatMessageRowDto(conversationId, message.role(), message.content(), contextOf(message.content()), json, null);
	}

	private ChatMessageDto toMessage(ChatMessageRowDto row) {
		String status = null;
		List<Map<String, Object>> sources = List.of();
		List<Map<String, Object>> visuals = List.of();
		if (row.getResponseJson() != null) {
			try {
				Map<String, Object> response = objectMapper.readValue(row.getResponseJson(), new TypeReference<Map<String, Object>>() {});
				status = (String) response.get("status");
				Object saved = response.get("sources");
				if (saved != null) sources = objectMapper.convertValue(saved, new TypeReference<List<Map<String, Object>>>() {});
				Object savedVisuals = response.get("visuals");
				if (savedVisuals != null) visuals = objectMapper.convertValue(savedVisuals, new TypeReference<List<Map<String, Object>>>() {});
			} catch (JsonProcessingException e) {
				log.warn("저장된 AI 응답 읽기 실패: {}", e.getMessage());
			}
		}
		String createdAt = row.getCreatedAt() == null ? null
				: row.getCreatedAt().atZone(MarketHours.KST).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString();
		return new ChatMessageDto(row.getRole(), row.getContent(), status, sources, visuals, createdAt);
	}

	private static String contextOf(String content) {
		return content.length() > CONTEXT_LENGTH ? content.substring(0, CONTEXT_LENGTH) : content;
	}

	private static String title(String question) {
		return question.length() > TITLE_LENGTH ? question.substring(0, TITLE_LENGTH) : question;
	}

	public static class ConversationNotFoundException extends RuntimeException {
		public ConversationNotFoundException() {
			super("대화방을 찾을 수 없습니다.");
		}
	}
}
