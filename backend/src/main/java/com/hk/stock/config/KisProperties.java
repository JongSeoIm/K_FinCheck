package com.hk.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

//application.properties의 kis.* 설정값 (키 값은 환경 변수로 주입)
@Data
@ConfigurationProperties(prefix = "kis")
public class KisProperties {
	private String appKey;
	private String appSecret;
	private String restUrl;
	private String wsUrl;
	private String tokenCacheFile;
	private long minIntervalMs = 250;

	//앱키·시크릿이 모두 있어야 실제 KIS 데이터를 사용한다
	public boolean isConfigured() {
		return appKey != null && !appKey.isBlank()
				&& appSecret != null && !appSecret.isBlank();
	}
}
