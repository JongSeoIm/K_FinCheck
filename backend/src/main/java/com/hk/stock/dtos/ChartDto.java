package com.hk.stock.dtos;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//차트 응답
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartDto {
	private String symbol;
	private String range;		//1D(당일 분봉), 1W·1M·1Y(일봉)
	private String interval;	//1m, 1d
	private String source;		//KIS / DEMO
	private List<ChartPointDto> points;
}
