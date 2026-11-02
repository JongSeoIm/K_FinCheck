package com.hk.stock.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//종목 관련 뉴스 기사 1건 (HTML 태그를 지운 일반 텍스트)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsDto {
	private String id;			//기사 원문 주소 (중복 판단 기준)
	private String headline;	//제목
	private String summary;		//요약
	private String source;		//언론사 (원문 주소의 도메인으로 판단)
	private long datetime;		//발행 시각 (Unix 초)
	private String url;			//원문 주소
	private boolean related;	//true: 종목 기사 / false: 기타 (이름만 언급·의견 글·정치 기사)
}
