package com.hk.stock.dtos;

import java.util.List;

import lombok.Builder;
import lombok.Data;

//실적 조회 응답: GET /api/companies/{symbol}/financials (AI 챗봇 get_financial_result Tool 이 사용)
@Data
@Builder
public class FinancialResultDto {
	private String symbol;
	private String name;
	private String source;			//OpenDART
	private String rceptNo;			//공시 접수번호
	private String url;				//DART 공시 원문 주소
	private String bsnsYear;		//사업연도
	private String reprtCode;
	private String reportName;		//1분기보고서·반기보고서·3분기보고서·사업보고서
	private String fsDiv;			//CFS / OFS
	private String fsName;			//연결 / 별도
	private String incomeBasis;		//손익 항목 금액의 기준 (예: 이번 분기 3개월, 비교는 전년 같은 분기)
	private String balanceBasis;	//재무상태 항목 금액의 기준
	private String unit;			//원
	private List<Item> items;
	private List<AnnualEarningsDto.Metric> metrics;	//영업이익률·부채비율·유동비율·매출 증감률

	@Data
	@Builder
	public static class Item {
		private String accountId;
		private String accountName;
		private String statement;	//IS 손익 / BS 재무상태 / ETC
		private Long current;		//당기
		private Long previous;		//전기
		private String currency;
	}
}
