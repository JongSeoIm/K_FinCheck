package com.hk.stock.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.NewsDto;

//네이버 뉴스 응답 변환·종목 기사 분류 규칙 확인 (네트워크 없이 실행)
class NewsParsersTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final List<String> SAMSUNG = List.of("삼성전자", "005930", "삼전", "만전자");

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
	void 태그와_엔티티를_지우고_중복과_위험한_주소를_제외한다() throws Exception {
		List<NewsDto> news = NewsParsers.items(objectMapper.readTree(BODY), SAMSUNG, 20);

		assertEquals(2, news.size());
		NewsDto older = news.get(1);
		assertEquals("삼성전자, 3분기 \"영업이익\" 증가", older.getHeadline());
		assertEquals("삼성전자가 & 실적을 발표했다.", older.getSummary());
		assertEquals("한국경제", older.getSource());
		assertEquals("https://www.hankyung.com/article/1", older.getUrl());
	}

	@Test
	void 최신순으로_정렬하고_원문주소가_없으면_네이버_주소를_쓴다() throws Exception {
		List<NewsDto> news = NewsParsers.items(objectMapper.readTree(BODY), SAMSUNG, 20);

		assertEquals("반도체 신제품 출시", news.get(0).getHeadline());
		assertEquals("네이버뉴스", news.get(0).getSource());
		assertTrue(news.get(0).getDatetime() > news.get(1).getDatetime());
		assertEquals(1793577600L, news.get(1).getDatetime());	//2026-11-02 09:00 KST
	}

	@Test
	void 종목_기사와_기타를_각각_limit_개까지_돌려준다() throws Exception {
		List<NewsDto> news = NewsParsers.items(objectMapper.readTree(BODY), SAMSUNG, 1);

		//종목 기사 1건(제목에 삼성전자) + 기타 1건(이름만 언급)
		assertEquals(2, news.size());
		assertEquals(1, news.stream().filter(NewsDto::isRelated).count());
	}

	@Test
	void 제목에_종목명이나_줄임말이_있으면_종목_기사다() {
		assertTrue(NewsParsers.isRelated("KB증권 \"삼성전자 내년 영업익 640조원\" 목표가 유지", "", SAMSUNG));
		assertTrue(NewsParsers.isRelated("60만전자 온다...삼전 W자 반등", "", SAMSUNG));
	}

	@Test
	void 제목에_없어도_요약의_종목_문장이_주가_이야기면_종목_기사다() {
		assertTrue(NewsParsers.isRelated("코스피, 장 초반 상승 전환",
				"코스닥도 올랐다. 삼성전자는 2% 넘게 오른 23만 원 선에 거래 중입니다.", SAMSUNG));
	}

	@Test
	void 이름만_스친_기사는_기타다() {
		assertFalse(NewsParsers.isRelated("\"아이들 밥상도 못 지키면서 무슨 미래\"…전남광주시 교육재정 도마",
				"특히 지난달 20일 민형배 시장의 삼성전자 평택캠퍼스 방문 이후 교육여건 개선을 강조했다.", SAMSUNG));
	}

	@Test
	void 시장_환율_기사에_종목명만_스치면_기타다() {
		assertFalse(NewsParsers.isRelated("외국인 배당금 최대 16.8조 해외로…씨티 “환율 1270원까지 하락”",
				"삼성전자와 SK하이닉스의 대규모 배당금 지급으로 외화 유출이 늘어날 것으로 예상되지만 수출기업의 환전 수요가 이를 웃돌면서 원화 강세 흐름은 이어질 것이라는 분석이다.", SAMSUNG));
		assertFalse(NewsParsers.isRelated("'8700 vs 4200' 코스피 극과 극 전망",
				"코스피 대표 종목인 삼성전자의 내년도 예상 실적 PER은 3.12배에 불과하다.", SAMSUNG));
	}

	@Test
	void 계열사_이름은_종목명으로_보지_않는다() {
		assertFalse(NewsParsers.isRelated("삼성전자서비스, 에어컨 AS 품질 2년 연속 1위", "삼성전자서비스가 서비스 품질 평가에서 1위를 했다.", SAMSUNG));
	}

	@Test
	void 시황_기사의_종목_등락_문장은_종목_기사다() {
		assertTrue(NewsParsers.isRelated("코스피, 장중 급등락 끝 0.6% 상승", "삼성전자는 0.68% 내린 21만8000원에 거래를 마쳤다.", SAMSUNG));
	}

	@Test
	void 의견_글과_정치_기사는_제목에_종목명이_있어도_기타다() {
		assertFalse(NewsParsers.isRelated("[부산 변호사 한변의 리얼상담실 66] 주성엔지니어링 주가 7배",
				"매출의 84%가 SK하이닉스에서 나왔고 주가는 7배 올랐다.", List.of("SK하이닉스", "000660")));
		assertFalse(NewsParsers.isRelated("野 지도부, 대여 공세 강화",
				"삼성전자 ETF 주가 급락 논란을 거론했다.", SAMSUNG));
		assertFalse(NewsParsers.isRelated("[기고] 삼성전자 반도체 특구의 그늘", "", SAMSUNG));
	}
}
