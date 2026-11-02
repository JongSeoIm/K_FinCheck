package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

//종목 최신 시세 (REST 응답과 WebSocket Push 메시지에 같은 형식으로 사용)
@Data
@Builder(toBuilder = true)
public class QuoteDto {
	private String symbol;
	private String name;
	private BigDecimal price;			//현재가
	private BigDecimal change;			//전일 대비 (원, 하락이면 음수)
	private BigDecimal changeRate;		//전일 대비 등락률 (%, 하락이면 음수)
	private BigDecimal open;			//시가
	private BigDecimal high;			//고가
	private BigDecimal low;				//저가
	private BigDecimal previousClose;	//전일 종가 (기준가)
	private Long volume;				//누적 거래량
	private String tradeTime;			//체결 시각 (거래소 기준, ISO-8601 +09:00)
	private long updatedAt;				//서버가 이 시세를 갱신한 시각 (epoch ms) - 늦게 온 값 덮어쓰기 방지용
	private String source;				//KIS / DEMO
}
