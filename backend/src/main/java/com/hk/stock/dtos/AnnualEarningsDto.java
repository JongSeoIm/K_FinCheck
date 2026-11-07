package com.hk.stock.dtos;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;
import lombok.Data;

//연간 공시 실적과 재무 지표: GET /api/companies/{symbol}/annual-earnings
@Data
@Builder
public class AnnualEarningsDto {
	private String symbol;
	private String name;
	private String source;			//OpenDART
	private String bsnsYear;		//사업연도
	private String reportName;		//사업보고서
	private String fsName;			//연결 / 별도
	private String url;				//DART 공시 원문
	private String unit;			//원
	private List<Item> items;		//매출액·영업이익·당기순이익
	private List<Metric> metrics;	//영업이익률·부채비율·유동비율·매출 증감률

	@Data
	@Builder
	public static class Item {
		private String code;
		private String name;
		private Long current;			//당기 금액 (없으면 null)
		private Long previous;			//전기 금액 (없으면 null)
		private BigDecimal changeRate;	//전년 대비 증감률 % (비교할 수 없으면 null)
		private String compareStatus;	//NORMAL / TURN_PROFIT 흑자 전환 / TURN_LOSS 적자 전환 / LOSS_CONTINUE 적자 지속 / NOT_COMPARABLE 비교 불가
	}

	@Data
	@Builder
	public static class Metric {
		private String code;
		private String name;
		private BigDecimal value;	//% (계산할 수 없으면 null, 0으로 바꾸지 않는다)
		private String status;		//OK / NOT_CALCULABLE 계산 불가 / MISSING 자료 없음
	}
}
