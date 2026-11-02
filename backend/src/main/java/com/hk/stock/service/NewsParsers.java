package com.hk.stock.service;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.hk.stock.dtos.NewsDto;

/**
 * 네이버 뉴스 검색 응답 → NewsDto 변환 (실습 lib/news.js 의 normalizeNewsItems 와 같은 규칙)
 * - 제목·요약의 HTML 태그(<b> 등)와 &quot; 같은 문자 엔티티를 지우고 길이를 제한한다.
 * - http/https 주소만 사용하고, 같은 주소·같은 제목은 한 번만 남긴다.
 * - 종목 기사와 기타 기사(이름만 언급·의견 글·정치 기사)로 나누고, 각각 최신순 limit 개만 돌려준다.
 */
public final class NewsParsers {

	private static final DateTimeFormatter PUB_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

	private static final Map<String, String> ENTITIES = Map.of(
			"&quot;", "\"", "&apos;", "'", "&#39;", "'", "&lt;", "<", "&gt;", ">", "&nbsp;", " ", "&middot;", "·");

	//원문 주소의 도메인 → 언론사 이름 (없는 도메인은 도메인 그대로 표시)
	private static final Map<String, String> SOURCES = Map.ofEntries(
			Map.entry("hankyung.com", "한국경제"), Map.entry("mk.co.kr", "매일경제"),
			Map.entry("yna.co.kr", "연합뉴스"), Map.entry("yonhapnewstv.co.kr", "연합뉴스TV"),
			Map.entry("edaily.co.kr", "이데일리"), Map.entry("mt.co.kr", "머니투데이"),
			Map.entry("sedaily.com", "서울경제"), Map.entry("fnnews.com", "파이낸셜뉴스"),
			Map.entry("asiae.co.kr", "아시아경제"), Map.entry("heraldcorp.com", "헤럴드경제"),
			Map.entry("newsis.com", "뉴시스"), Map.entry("news1.kr", "뉴스1"),
			Map.entry("chosun.com", "조선일보"), Map.entry("joongang.co.kr", "중앙일보"),
			Map.entry("donga.com", "동아일보"), Map.entry("hani.co.kr", "한겨레"),
			Map.entry("etnews.com", "전자신문"), Map.entry("zdnet.co.kr", "지디넷코리아"),
			Map.entry("inews24.com", "아이뉴스24"), Map.entry("ddaily.co.kr", "디지털데일리"),
			Map.entry("biz.chosun.com", "조선비즈"), Map.entry("thebell.co.kr", "더벨"),
			Map.entry("sbs.co.kr", "SBS"), Map.entry("kbs.co.kr", "KBS"), Map.entry("imbc.com", "MBC"),
			Map.entry("jtbc.co.kr", "JTBC"), Map.entry("ytn.co.kr", "YTN"), Map.entry("mbn.co.kr", "MBN"),
			Map.entry("khan.co.kr", "경향신문"), Map.entry("hankookilbo.com", "한국일보"), Map.entry("kmib.co.kr", "국민일보"),
			Map.entry("seoul.co.kr", "서울신문"), Map.entry("nocutnews.co.kr", "노컷뉴스"), Map.entry("newspim.com", "뉴스핌"),
			Map.entry("dt.co.kr", "디지털타임스"), Map.entry("ajunews.com", "아주경제"), Map.entry("g-enews.com", "글로벌이코노믹"),
			Map.entry("businesspost.co.kr", "비즈니스포스트"), Map.entry("bizwatch.co.kr", "비즈니스워치"), Map.entry("ebn.co.kr", "EBN"),
			Map.entry("naver.com", "네이버뉴스"));

	//제목에 있으면 "기타"로 보내는 패턴: 기고·칼럼·사설·상담 같은 의견 글, 정치 기사
	private static final Pattern EXCLUDED_HEADLINE = Pattern.compile(
			"기고|칼럼|사설|오피니언|시론|기자수첩|상담실|"
			+ "野|與|여당|야당|국민의힘|국힘|민주당|시정연설|지도부|국정감사|국감|의원");

	//요약 문장이 주식·실적 이야기인지 판단하는 단어
	private static final List<String> MARKET_WORDS = List.of(
			"주가", "주식", "상승", "하락", "강세", "약세", "급등", "급락", "오른", "올라", "내린", "떨어", "원에 거래",
			"목표가", "목표주가", "투자의견", "실적", "영업이익", "매출", "순이익", "이익", "배당", "주주환원", "자사주",
			"시가총액", "시총", "순매수", "순매도", "매수", "매도", "코스피", "코스닥", "증시", "증권", "공급계약", "수주",
			"수익", "적자", "흑자", "점유율", "출하량", "판매량", "%");

	private NewsParsers() {
	}

	/**
	 * 기사를 "종목 기사"(related=true)와 "기타"(related=false)로 나눠 각각 최신순 limit 개까지 돌려준다.
	 * @param keywords 종목명·줄임말·종목코드 (예: 삼성전자, 삼전, 005930)
	 */
	public static List<NewsDto> items(JsonNode body, List<String> keywords, int limit) {
		List<NewsDto> related = new ArrayList<>();
		List<NewsDto> others = new ArrayList<>();
		Set<String> seenUrls = new HashSet<>();
		Set<String> seenHeadlines = new HashSet<>();

		for (JsonNode item : body.path("items")) {
			String headline = plainText(item.path("title").asText(""), 240);
			String original = safeHttpUrl(item.path("originallink").asText(""));
			String url = original.isEmpty() ? safeHttpUrl(item.path("link").asText("")) : original;
			if (headline.isEmpty() || url.isEmpty()) continue;
			//같은 주소 또는 같은 제목이면 중복 기사로 판단
			if (!seenUrls.add(url) || !seenHeadlines.add(headline)) continue;

			String summary = plainText(item.path("description").asText(""), 600);
			boolean isRelated = isRelated(headline, summary, keywords);
			NewsDto news = new NewsDto(url, headline, summary, source(url),
					epochSeconds(item.path("pubDate").asText("")), url, isRelated);
			(isRelated ? related : others).add(news);
		}

		List<NewsDto> result = new ArrayList<>(latest(related, limit));
		result.addAll(latest(others, limit));
		result.sort(Comparator.comparingLong(NewsDto::getDatetime).reversed());
		return result;
	}

	/**
	 * 종목 기사 판단
	 * 1. 제목이 의견 글·정치 기사 패턴이면 → 기타
	 * 2. 제목에 종목명·줄임말이 있으면 → 종목 기사
	 * 3. 요약에서 종목명이 나오는 문장에 주가·실적 단어가 있으면 → 종목 기사
	 * 4. 그 외 (이름만 스친 기사) → 기타
	 */
	static boolean isRelated(String headline, String summary, List<String> keywords) {
		if (EXCLUDED_HEADLINE.matcher(headline).find()) return false;
		if (containsAny(headline, keywords)) return true;
		//네이버 요약은 검색어 주변 문장을 잘라 준 것이라, 종목명이 있는 문장만 본다
		for (String sentence : summary.split("(?<=[.!?…])\\s+|(?<=다\\.)")) {
			if (containsAny(sentence, keywords) && containsAny(sentence, MARKET_WORDS)) return true;
		}
		return false;
	}

	private static boolean containsAny(String text, List<String> words) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String word : words) {
			if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}

	private static List<NewsDto> latest(List<NewsDto> list, int limit) {
		list.sort(Comparator.comparingLong(NewsDto::getDatetime).reversed());
		return list.size() > limit ? list.subList(0, limit) : list;
	}

	static String plainText(String value, int maxLength) {
		String text = value.replaceAll("<[^>]*>", "");	//네이버는 <b>를 단어 중간에 넣으므로 공백 없이 지운다
		for (Map.Entry<String, String> e : ENTITIES.entrySet()) {
			text = text.replace(e.getKey(), e.getValue());
		}
		text = text.replace("&amp;", "&").replaceAll("\\s+", " ").trim();
		return text.length() > maxLength ? text.substring(0, maxLength) : text;
	}

	static String safeHttpUrl(String value) {
		try {
			URI uri = URI.create(value.trim());
			String scheme = uri.getScheme();
			if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
				return "";
			}
			return uri.toString();
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	static String source(String url) {
		String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
		if (host.startsWith("www.")) host = host.substring(4);
		//biz.chosun.com 처럼 더 구체적인 도메인을 먼저 찾는다
		String name = SOURCES.get(host);
		if (name != null) return name;
		for (Map.Entry<String, String> e : SOURCES.entrySet()) {
			if (host.endsWith("." + e.getKey()) || host.equals(e.getKey())) return e.getValue();
		}
		return host;
	}

	static long epochSeconds(String pubDate) {
		try {
			return ZonedDateTime.parse(pubDate.trim(), PUB_DATE).toEpochSecond();
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
