package com.hk.stock.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//네이버 뉴스 검색 결과 1건 (HTML 태그만 지운 일반 텍스트)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsDto {
	private String id;			//기사 원문 주소
	private String headline;	//제목
	private String summary;		//요약
	private String source;		//언론사 (원문 주소의 도메인으로 판단)
	private long datetime;		//발행 시각 (Unix 초)
	private String url;			//원문 주소
}
