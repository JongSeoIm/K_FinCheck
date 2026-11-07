package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

//증권사 리포트 1건의 투자의견 (KIS 종목투자의견 한 줄)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpinionReportDto {
	private String date;			//리포트 날짜 (yyyy-MM-dd)
	private String broker;			//증권사
	private String opinion;			//원문 투자의견 (예: BUY, 매수, Outperform)
	private String category;		//5단계 분류: strongBuy / buy / hold / sell / strongSell / other
	private BigDecimal targetPrice;	//목표가 (원, 없으면 null)
}
