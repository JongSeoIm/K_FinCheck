package com.hk.stock.dtos;

import java.util.List;
import java.util.Map;

//대화 메시지 1개 (화면·AI 문맥에 쓰는 형태, DB 행은 ChatMessageRowDto)
//role: user / assistant, sources: AI 답변의 출처 (사용자 질문은 빈 목록)
public record ChatMessageDto(String role, String content, String status, List<Map<String, Object>> sources, List<Map<String, Object>> visuals, String createdAt) {
}
