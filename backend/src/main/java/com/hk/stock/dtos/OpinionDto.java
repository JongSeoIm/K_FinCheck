package com.hk.stock.dtos;

import java.util.List;

import lombok.Builder;
import lombok.Data;

//전문가 투자의견 요약 (목업 화면의 점수·분포·최근 월별 추세)
@Data
@Builder
public class OpinionDto {
	private String symbol;
	private String source;					//KIS / DEMO
	private String from;					//조회 시작일 (yyyy-MM-dd)
	private String to;						//조회 종료일
	private int reportCount;				//기간 내 리포트 수
	private int brokerCount;				//의견을 낸 증권사 수
	private Integer score;					//매수 비율 0~100 (증권사별 최신 의견 기준, 분류된 의견이 없으면 null)
	private OpinionCountsDto latest;		//증권사별 가장 최근 의견 집계
	private List<OpinionCountsDto> months;	//월별 리포트 의견 (오래된 순)
}
