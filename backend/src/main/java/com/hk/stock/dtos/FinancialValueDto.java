package com.hk.stock.dtos;

import org.apache.ibatis.type.Alias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//공시의 재무 수치 1개 (financial_value 테이블, 금액 단위: 원)
@Alias(value = "financialValueDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinancialValueDto {
	private Long reportId;
	private String accountId;	//계정 코드 (FinancialAccounts 의 REVENUE·OPERATING_INCOME 등)
	private String accountNm;	//공시에 적힌 계정 이름 (매출액, 영업이익 …)
	private Long thstrmAmount;	//당기 금액 (값이 없으면 null)
	private Long frmtrmAmount;	//전기 금액 (값이 없으면 null)
	private String currency;	//통화 (KRW)
}
