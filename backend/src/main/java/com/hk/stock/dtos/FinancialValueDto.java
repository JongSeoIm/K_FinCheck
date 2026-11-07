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
	private Long valueId;
	private Long reportId;
	private String accountCode;	//계정 코드 (FinancialAccounts 의 REVENUE·OPERATING_INCOME 등)
	private String accountName;	//공통 계정 이름 (매출액, 영업이익 …)
	private String sourceAccountId;	//DART 원본 계정 ID (주요계정 API 는 주지 않아 null)
	private String sourceAccountName;	//공시에 적힌 계정 이름 (수익(매출액) …)
	private Long currentAmount;	//당기 금액 (값이 없으면 null)
	private Long previousAmount;	//전기 금액 (값이 없으면 null)
	private String currency;	//통화 (KRW)
	private String valueStatus;	//AVAILABLE / MISSING (당기 금액 누락. 0으로 바꾸지 않는다)
}
