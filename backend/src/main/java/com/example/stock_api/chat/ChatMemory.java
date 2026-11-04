package com.example.stock_api.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 사용자별 최근 대화 보관소 (서버 메모리).
 * - 사용자·AI 메시지를 합쳐 최근 10개만 유지합니다.
 * - 로그아웃하거나 '새 대화'를 누르면 지우고, 서버를 다시 시작하면 사라집니다.
 * - 회원 번호(userId)로만 찾으므로 다른 사용자의 대화는 볼 수 없습니다.
 */
@Component
public class ChatMemory {

    public static final int MAX_MESSAGES = 10;

    public record Message(String role, String content, List<Object> sources) {}

    private final Map<Long, Deque<Message>> store = new ConcurrentHashMap<>();

    public List<Message> get(long userId) {
        Deque<Message> deque = store.get(userId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void append(long userId, Message user, Message assistant) {
        Deque<Message> deque = store.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(user);
            deque.addLast(assistant);
            while (deque.size() > MAX_MESSAGES) {
                deque.removeFirst();
            }
        }
    }

    public void clear(long userId) {
        store.remove(userId);
    }
}
