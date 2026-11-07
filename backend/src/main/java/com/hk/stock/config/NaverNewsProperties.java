package com.hk.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

//application.properties의 naver.news.* 설정값 (키 값은 .env 로 주입)
@Data
@ConfigurationProperties(prefix = "naver.news")
public class NaverNewsProperties {
	private String clientId;
	private String clientSecret;
	private String url = "https://naverapihub.apigw.ntruss.com/search/v1/news";	//NAVER API HUB 뉴스 검색
	private int cacheSeconds = 300;

	//Client ID·Secret이 모두 있어야 뉴스를 조회한다
	public boolean isConfigured() {
		return clientId != null && !clientId.isBlank()
				&& clientSecret != null && !clientSecret.isBlank();
	}
}
