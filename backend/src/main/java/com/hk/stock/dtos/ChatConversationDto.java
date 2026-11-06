package com.hk.stock.dtos;

import java.time.LocalDateTime;

import org.apache.ibatis.type.Alias;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//대화방 1개 (chat_conversation 테이블). DB 시각은 한국 시간이다
@Alias(value = "chatConversationDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatConversationDto {
	private String conversationId;
	private String stockCode;
	private String stockName;
	private String title;			//첫 질문 (질문 전에는 '새 대화')
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'+09:00'")
	private LocalDateTime createdAt;
	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'+09:00'")
	private LocalDateTime updatedAt;	//마지막으로 대화를 이어간 시각
}
