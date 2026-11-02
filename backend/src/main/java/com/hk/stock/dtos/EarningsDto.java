package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

//최근 실적 1분기 (누적값에서 계산한 해당 분기 단독 값)
@Data
@Builder
public class EarningsDto {
	private String period;				//예: 2026 Q2
	private int year;
	private int quarter;
	private BigDecimal revenue;			//매출액 (억원)
	private BigDecimal operatingProfit;	//영업이익 (억원)
	private BigDecimal operatingMargin;	//영업이익률 (%) = 영업이익 / 매출액 × 100
	private BigDecimal eps;				//주당순이익 (원)
}
