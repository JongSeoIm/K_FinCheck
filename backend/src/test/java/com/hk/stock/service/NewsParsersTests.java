package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.NewsDto;

//네이버 뉴스 응답 변환 확인 (네트워크 없이 실행)
class NewsParsersTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final String BODY = """
			{"items":[
			 {"title":"<b>삼성전자</b>, 3분기 &quot;영업이익&quot; 증가",
			  "originallink":"https://www.hankyung.com/article/1","link":"https://n.news.naver.com/a/1",
			  "description":"<b>삼성전자</b>가 &amp; 실적을 발표했다.","pubDate":"Mon, 02 Nov 2026 09:00:00 +0900"},
			 {"title":"반도체 신제품 출시","originallink":"","link":"https://n.news.naver.com/a/2",
			  "description":"삼성전자가 새 메모리를 공개했다.","pubDate":"Mon, 02 Nov 2026 11:00:00 +0900"},
			 {"title":"중복 기사","originallink":"https://www.hankyung.com/article/1","link":"",
			  "description":"","pubDate":"Mon, 02 Nov 2026 10:00:00 +0900"},
			 {"title":"위험한 주소","originallink":"javascript:alert(1)","link":"",
			  "description":"","pubDate":"Mon, 02 Nov 2026 12:00:00 +0900"}
			]}
			""";

	@Test
	void 태그와_엔티티를_지우고_위험한_주소를_제외한다() throws Exception {
		List<NewsDto> news = NewsParsers.items(objectMapper.readTree(BODY));

		assertEquals(2, news.size());
		NewsDto first = news.get(0);
		assertEquals("삼성전자, 3분기 \"영업이익\" 증가", first.getHeadline());
		assertEquals("삼성전자가 & 실적을 발표했다.", first.getSummary());
		assertEquals("한국경제", first.getSource());
		assertEquals("https://www.hankyung.com/article/1", first.getUrl());
		assertEquals(1793577600L, first.getDatetime());	//2026-11-02 09:00 KST
	}

	@Test
	void 받은_순서를_유지하고_같은_주소는_한_번만_남긴다() throws Exception {
		List<NewsDto> news = NewsParsers.items(objectMapper.readTree(BODY));

		assertEquals(List.of("삼성전자, 3분기 \"영업이익\" 증가", "반도체 신제품 출시"),
				news.stream().map(NewsDto::getHeadline).toList());
		assertEquals("네이버뉴스", news.get(1).getSource());
	}

	@Test
	void 같은_제목은_주소가_달라도_한_번만_남긴다() throws Exception {
		List<NewsDto> news = NewsParsers.items(objectMapper.readTree("""
				{"items":[
				 {"title":"같은 제목","originallink":"https://a.example/1","pubDate":""},
				 {"title":"<b>같은</b> 제목","originallink":"https://b.example/2","pubDate":""},
				 {"title":"다른 제목","originallink":"https://c.example/3","pubDate":""}
				]}
				"""));

		assertEquals(List.of("같은 제목", "다른 제목"), news.stream().map(NewsDto::getHeadline).toList());
		assertEquals("https://a.example/1", news.get(0).getUrl());
	}
}
