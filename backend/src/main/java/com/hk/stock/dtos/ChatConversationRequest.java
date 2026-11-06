package com.hk.stock.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

//새 대화방: POST /api/chat/conversations
@Data
public class ChatConversationRequest {
	@NotBlank(message = "종목코드를 입력하세요.")
	@Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자입니다.")
	private String symbol;
}
