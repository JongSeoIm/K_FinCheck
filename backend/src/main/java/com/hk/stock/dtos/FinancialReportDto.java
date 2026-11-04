package com.hk.stock.dtos;

import java.time.LocalDateTime;

import org.apache.ibatis.type.Alias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//OpenDART 공시 1건 (financial_report 테이블). 같은 접수번호라도 연결/별도는 따로 저장한다
@Alias(value = "financialReportDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinancialReportDto {
	private Long reportId;
	private String rceptNo;		//공시 접수번호 14자리
	private String symbol;
	private String bsnsYear;	//사업연도
	private String reprtCode;	//11013 1분기 / 11012 반기 / 11014 3분기 / 11011 사업보고서
	private String fsDiv;		//CFS 연결 / OFS 별도
	private LocalDateTime regdate;
}
