package com.hk.stock.dtos;

import java.time.LocalDateTime;

import org.apache.ibatis.type.Alias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//계정별 관심 종목 (watchlist + company 테이블)
@Alias(value = "watchlistDto")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistDto {
	private String symbol;			//종목코드
	private String name;			//종목명
	private String market;			//KOSPI, KOSDAQ
	private LocalDateTime regdate;	//등록일
}
