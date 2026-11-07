package com.hk.stock.dtos;

import java.time.LocalDateTime;

import org.apache.ibatis.type.Alias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//chat_message 한 줄
@Alias(value = "chatMessageRowDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageRowDto {
	private String conversationId;
	private String role;			//user / assistant
	private String content;			//화면에 보여 주는 내용
	private String contextContent;	//AI 문맥으로 보내는 내용
	private String responseJson;	//AI 응답의 status·sources·visuals
	private LocalDateTime createdAt;
}
