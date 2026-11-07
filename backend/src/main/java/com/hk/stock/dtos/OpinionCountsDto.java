package com.hk.stock.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

//투자의견 5단계별 건수 (월별 추세 1칸, 또는 증권사별 최신 의견 집계)
@Data
@NoArgsConstructor
public class OpinionCountsDto {
	private String period;		//월별이면 yyyy-MM, 최신 의견 집계면 null
	private int strongBuy;
	private int buy;
	private int hold;
	private int sell;
	private int strongSell;
	private int other;			//분류하지 못한 의견 (점수 계산에서 제외)

	public OpinionCountsDto(String period) {
		this.period = period;
	}

	public void add(String category) {
		switch (category) {
			case "strongBuy" -> strongBuy++;
			case "buy" -> buy++;
			case "hold" -> hold++;
			case "sell" -> sell++;
			case "strongSell" -> strongSell++;
			default -> other++;
		}
	}

	//분류된 의견 수 (기타 제외)
	public int rated() {
		return strongBuy + buy + hold + sell + strongSell;
	}
}
