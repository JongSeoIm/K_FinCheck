package com.hk.stock.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.hk.stock.config.SecurityConfig;
import com.hk.stock.dtos.ChatConversationDto;
import com.hk.stock.dtos.ChatConversationRequest;
import com.hk.stock.dtos.ChatMessageDto;
import com.hk.stock.dtos.ChatRequest;
import com.hk.stock.service.ChatService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

//AI 챗봇 (SecurityConfig 가 로그인 여부를 먼저 확인한다. 로그인한 사용자만 이용)
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatController {

	private static final String SYMBOL_REGEX = "\\d{6}";
	private static final String SYMBOL_MESSAGE = "종목코드는 6자리 숫자입니다.";
	private static final String ROOM_REGEX = "[0-9a-fA-F-]{36}";
	private static final String ROOM_MESSAGE = "대화방 번호가 올바르지 않습니다.";

	@Autowired
	private ChatService chatService;

	//질문: POST /api/chat {"symbol":"005930","conversationId":"...","question":"..."} → {"conversationId","status","answer","sources","visuals"}
	@PostMapping
	public Map<String, Object> ask(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@Valid @RequestBody ChatRequest body) {
		return chatService.ask(userId, body.getSymbol(), body.getConversationId(), body.getQuestion());
	}

	//새 대화방: POST /api/chat/conversations {"symbol":"005930"} → 201 {"conversation":{...}}
	@PostMapping("/conversations")
	public ResponseEntity<Map<String, ChatConversationDto>> create(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@Valid @RequestBody ChatConversationRequest body) {
		Map<String, ChatConversationDto> map = new HashMap<>();
		map.put("conversation", chatService.create(userId, body.getSymbol()));
		return ResponseEntity.status(HttpStatus.CREATED).body(map);
	}

	//종목의 대화방 목록 (최근에 이어간 순서): GET /api/chat/conversations?symbol=005930 → {"conversations":[...]}
	@GetMapping("/conversations")
	public Map<String, List<ChatConversationDto>> conversations(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@RequestParam @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, List<ChatConversationDto>> map = new HashMap<>();
		map.put("conversations", chatService.conversations(userId, symbol));
		return map;
	}

	//대화방의 저장된 메시지: GET /api/chat/conversations/{conversationId}/messages → {"messages":[...]}
	@GetMapping("/conversations/{conversationId}/messages")
	public Map<String, List<ChatMessageDto>> messages(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@PathVariable @Pattern(regexp = ROOM_REGEX, message = ROOM_MESSAGE) String conversationId) {
		Map<String, List<ChatMessageDto>> map = new HashMap<>();
		map.put("messages", chatService.messages(userId, conversationId));
		return map;
	}

	//대화방 삭제: DELETE /api/chat/conversations/{conversationId} → 204
	@DeleteMapping("/conversations/{conversationId}")
	public ResponseEntity<Void> delete(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@PathVariable @Pattern(regexp = ROOM_REGEX, message = ROOM_MESSAGE) String conversationId) {
		chatService.delete(userId, conversationId);
		return ResponseEntity.noContent().build();
	}
}
