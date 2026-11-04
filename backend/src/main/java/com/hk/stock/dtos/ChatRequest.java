package com.hk.stock.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

//AI 질문: POST /api/chat/messages
@Data
public class ChatRequest {
	@NotBlank(message = "종목코드를 입력하세요.")
	@Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자입니다.")
	private String symbol;		//대화창을 연 종목 (질문에 기업명이 없으면 이 종목 기준)

	@NotBlank(message = "질문을 입력하세요.")
	@Size(max = 500, message = "질문은 500자 이내로 입력하세요.")
	private String question;
}
