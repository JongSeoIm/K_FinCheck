package com.hk.stock.dtos;

import java.util.List;
import java.util.Map;

//대화 메시지 1개 (서버 메모리에만 보관, DB에 저장하지 않는다)
//role: user / assistant, sources: AI 답변의 출처 (사용자 질문은 빈 목록)
public record ChatMessageDto(String role, String content, String status, List<Map<String, Object>> sources, String createdAt) {
}
