package com.hk.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

//application.properties의 ai.* 설정값 (Python FastAPI AI 서버)
@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
	private String serverUrl = "http://localhost:8087";
	private String internalKey;		//FastAPI 의 AI_INTERNAL_KEY 와 같은 값 (비우면 헤더를 보내지 않음)
	private int timeoutSeconds = 60;
}
