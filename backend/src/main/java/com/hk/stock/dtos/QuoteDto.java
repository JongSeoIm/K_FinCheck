package com.hk.stock.dtos;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

//종목 최신 시세 (REST 응답과 WebSocket Push 메시지에 같은 형식으로 사용)
//필드 이름은 주제 설명서 13.1 공통 이벤트 형식을 따른다
@Data
@Builder(toBuilder = true)
public class QuoteDto {
	private String eventId;				//서버가 붙인 이벤트 번호 (Kafka 발행 시 또는 REST 조회 시). 제공처 원천 ID가 아니다
	private String symbol;				//종목코드 (문자열, 앞자리 0 보존)
	private String name;
	private String market;				//시장 구분 (company.market, 예: KOSPI)
	private BigDecimal price;			//현재가
	private BigDecimal changeAmount;	//전일 대비 (원, 하락이면 음수)
	private BigDecimal changeRate;		//전일 대비 등락률 (%, 하락이면 음수)
	private BigDecimal open;			//시가
	private BigDecimal high;			//고가
	private BigDecimal low;				//저가
	private BigDecimal previousClose;	//전일 종가 (기준가)
	private Long cumulativeVolume;		//누적 거래량
	private String eventTime;			//체결 시각 (ISO-8601 +09:00). 실시간은 체결 초 단위, REST 현재가는 최근 체결 분. 확인하지 못하면 null
	private String receivedAt;			//서버가 이 시세를 받은 시각 (ISO-8601 +09:00, 밀리초) - 늦게 온 값 덮어쓰기 방지용
	private String source;				//KIS / DEMO (데이터 제공처)
	private String dataMode;			//KIS(실제 데이터) / DEMO(모의 데이터) - /api/market/status 의 dataMode 와 같은 값
}
