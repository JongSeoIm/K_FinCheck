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
	private String range;		//일봉 조회 기간 1W·1M·1Y, 1D(당일 분봉). 그 외에는 null
	private String interval;	//1s·5s·30s·1m·3m·5m·10m·15m·30m·60m·1d·1w·1mo·1y
	private String source;		//KIS / DEMO
	private List<ChartPointDto> points;
}
