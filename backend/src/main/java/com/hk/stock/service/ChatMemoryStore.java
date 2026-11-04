package com.hk.stock.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.hk.stock.dtos.ChatMessageDto;

/**
 * 대화 문맥 (팀 결정 사항)
 * - 회원번호 + 종목코드별로 사용자·AI 메시지를 합쳐 최근 10개만 유지한다 (11번째부터 가장 오래된 것부터 버림)
 * - 새 대화 버튼 → 해당 종목 대화 삭제, 로그아웃 → 그 회원의 대화 모두 삭제
 * - 서버 메모리에만 두므로 서버를 재시작하면 사라진다 (대화 저장 테이블은 범위 밖)
 */
@Component
public class ChatMemoryStore {

	public static final int MAX_MESSAGES = 10;

	private final Map<String, Deque<ChatMessageDto>> conversations = new ConcurrentHashMap<>();

	private static String key(Long userId, String symbol) {
		return userId + ":" + symbol;
	}

	//복사본을 돌려준다 (오래된 메시지가 먼저)
	public List<ChatMessageDto> get(Long userId, String symbol) {
		Deque<ChatMessageDto> messages = conversations.get(key(userId, symbol));
		if (messages == null) return List.of();
		synchronized (messages) {
			return List.copyOf(messages);
		}
	}

	public void append(Long userId, String symbol, ChatMessageDto... added) {
		Deque<ChatMessageDto> messages = conversations.computeIfAbsent(key(userId, symbol), k -> new ArrayDeque<>());
		synchronized (messages) {
			for (ChatMessageDto message : added) {
				messages.addLast(message);
				while (messages.size() > MAX_MESSAGES) messages.removeFirst();
			}
		}
	}

	public void clear(Long userId, String symbol) {
		conversations.remove(key(userId, symbol));
	}

	public void clearUser(Long userId) {
		String prefix = userId + ":";
		conversations.keySet().removeIf(k -> k.startsWith(prefix));
	}
}
