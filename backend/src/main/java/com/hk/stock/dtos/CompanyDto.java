package com.hk.stock.dtos;

import org.apache.ibatis.type.Alias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//기업·종목 정보 (company 테이블, DART 기업 고유번호 포함)
@Alias(value = "companyDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDto {
	private String symbol;			//종목코드
	private String name;			//기업명
	private String market;			//KOSPI, KOSDAQ
	private String dartCorpCode;	//OpenDART 기업 고유번호 8자리
}
