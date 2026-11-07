package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.config.NaverNewsProperties;
import com.hk.stock.dtos.NewsDto;
import com.hk.stock.dtos.StockDto;
import com.sun.net.httpserver.HttpServer;

//뉴스 서버 캐시 확인 (네이버 대신 로컬 서버가 호출 횟수를 센다)
class NewsServiceTests {

	private static final String BODY = """
			{"items":[{"title":"기사","originallink":"https://a.example/1","description":"","pubDate":""}]}
			""";

	private HttpServer server;
	private final AtomicInteger calls = new AtomicInteger();
	private volatile int status = 200;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/news", exchange -> {
			calls.incrementAndGet();
			byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private NewsService service(int cacheSeconds) {
		NaverNewsProperties props = new NaverNewsProperties();
		props.setClientId("id");
		props.setClientSecret("secret");
		props.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/news");
		props.setCacheSeconds(cacheSeconds);
		StockService stocks = mock(StockService.class);
		when(stocks.getStock("005930")).thenReturn(new StockDto("005930", "삼성전자", "KOSPI"));
		when(stocks.getStock("000660")).thenReturn(new StockDto("000660", "SK하이닉스", "KOSPI"));
		return new NewsService(props, stocks, new ObjectMapper(), RestClient.builder());
	}

	@Test
	void 캐시_시간_안에는_같은_종목을_다시_호출하지_않는다() {
		NewsService service = service(300);

		List<NewsDto> first = service.getNews("005930");
		assertSame(first, service.getNews("005930"));
		assertEquals(1, calls.get());

		service.getNews("000660");
		assertEquals(2, calls.get());
	}

	@Test
	void 캐시_시간이_지나면_다시_호출한다() {
		NewsService service = service(0);

		service.getNews("005930");
		service.getNews("005930");

		assertEquals(2, calls.get());
	}

	@Test
	void 실패한_결과는_저장하지_않는다() {
		NewsService service = service(300);

		status = 500;
		assertThrows(NewsService.NewsApiException.class, () -> service.getNews("005930"));

		status = 200;
		assertEquals(1, service.getNews("005930").size());
		assertEquals(2, calls.get());
	}
}
