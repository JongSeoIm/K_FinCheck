package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//차트 한 칸 (분봉 또는 일봉)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartPointDto {
	private String time;		//분봉: 2026-10-30T09:05:00+09:00 / 일봉: 2026-10-30
	private BigDecimal open;
	private BigDecimal high;
	private BigDecimal low;
	private BigDecimal close;
	private Long volume;
}
