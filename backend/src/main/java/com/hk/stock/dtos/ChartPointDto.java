package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//차트 한 칸 (초봉·분봉·일봉 이상)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartPointDto {
	private String time;		//초·분봉: 2026-10-30T09:05:00+09:00 / 일봉 이상: 2026-10-30
	private BigDecimal open;
	private BigDecimal high;
	private BigDecimal low;
	private BigDecimal close;
	private Long volume;
	private Boolean complete;	//false 면 아직 진행 중인 봉 (true·null 은 완성된 봉)

	public ChartPointDto(String time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) {
		this(time, open, high, low, close, volume, null);
	}
}
