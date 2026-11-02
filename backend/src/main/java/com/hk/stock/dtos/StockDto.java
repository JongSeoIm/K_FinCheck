package com.hk.stock.dtos;

import org.apache.ibatis.type.Alias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//지원 종목 정보 (stock_info 테이블)
@Alias(value = "stockDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockDto {
	private String symbol;	//종목코드 6자리 문자열 (앞자리 0 보존)
	private String name;	//종목명
	private String market;	//KOSPI, KOSDAQ
}
