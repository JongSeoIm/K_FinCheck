package com.hk.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

//application.properties의 dart.* 설정값 (키 값은 .env 의 DART_API_KEY 로 주입)
@Data
@ConfigurationProperties(prefix = "dart")
public class DartProperties {
	private String apiKey;
	private String url = "https://opendart.fss.or.kr/api/fnlttSinglAcnt.json";	//단일회사 주요계정
	//아직 공시되지 않은 보고서(조회 결과 없음)를 다시 묻기 전까지 기다리는 시간(초)
	private int missCacheSeconds = 21600;

	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
