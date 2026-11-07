package com.hk.stock.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.demo.DemoMarketDataProvider;
import com.hk.stock.market.kis.KisAuthService;
import com.hk.stock.market.kis.KisMarketDataProvider;
import com.hk.stock.market.kis.KisRealtimeClient;
import com.hk.stock.market.kis.KisRestClient;

/**
 * 시세 제공처 선택 (market.provider)
 * - kis: 한국투자증권 KIS (키가 없으면 서버가 시작되지 않고 이유를 알려준다)
 * - auto: KIS 키가 있으면 KIS, 없으면 모의 데이터
 * - demo: 모의 데이터 (화면 개발용)
 */
@Configuration
public class MarketDataConfig {

	private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);

	@Bean
	public MarketDataProvider marketDataProvider(@Value("${market.provider}") String provider,
			KisProperties kis,
			RestClient.Builder builder, ObjectMapper objectMapper) {

		String choice = provider == null ? "auto" : provider.trim().toLowerCase();
		if (!choice.equals("kis") && !choice.equals("demo") && !choice.equals("auto")) {
			log.warn("MARKET_PROVIDER={} 는 지원하지 않습니다. auto(KIS 키가 있으면 KIS)로 실행합니다.", provider);
			choice = "auto";
		}
		if ("auto".equals(choice)) {
			choice = kis.isConfigured() ? "kis" : "demo";
		}

		switch (choice) {
			case "kis" -> {
				require(kis.isConfigured(), "MARKET_PROVIDER=kis 인데 .env 의 KIS_APP_KEY / KIS_APP_SECRET 이 비어 있습니다.");
				RestClient restClient = restClient(builder);
				KisAuthService auth = new KisAuthService(kis, restClient, objectMapper);
				log.info("KIS 실제 데이터 모드로 실행합니다. (REST: {})", kis.getRestUrl());
				return new KisMarketDataProvider(
						new KisRestClient(kis, auth, restClient, objectMapper),
						new KisRealtimeClient(kis, auth, objectMapper));
			}
			case "demo" -> {
				log.warn("모의 데이터(DEMO) 모드로 실행합니다. 실제 시세가 아니며 화면에도 '모의 데이터'로 표시됩니다.");
				return new DemoMarketDataProvider();
			}
			default -> throw new IllegalStateException("MARKET_PROVIDER 값이 올바르지 않습니다: " + provider + " (kis, auto, demo)");
		}
	}

	private static RestClient restClient(RestClient.Builder builder) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5_000);
		factory.setReadTimeout(10_000);
		return builder.requestFactory(factory).build();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
