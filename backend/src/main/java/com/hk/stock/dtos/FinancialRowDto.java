package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//KIS 분기 재무 1줄. 값은 그해 1월부터 해당 분기까지의 누적값이다 (예: 202606 = 상반기 합계)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinancialRowDto {
	private String yearMonth;			//결산 년월 yyyyMM (03·06·09·12)
	private BigDecimal revenue;			//매출액 누적 (억원, 없으면 null)
	private BigDecimal operatingProfit;	//영업이익 누적 (억원)
	private BigDecimal eps;				//주당순이익 누적 (원)
}
