package com.hk.stock.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//시세 수신 상태 (화면의 연결 상태 표시용)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketStatusDto {
	private String dataMode;		//KIS(실제 데이터) / DEMO(모의 데이터)
	private String feedStatus;		//CONNECTING / CONNECTED / DISCONNECTED / DEMO / KAFKA_DOWN(Kafka 확인 메시지를 받지 못함)
	private boolean marketOpen;		//정규장 운영 시간 여부 (평일 09:00~15:30, 휴장일 미반영)
	private Long lastTickAt;		//마지막 실시간 체결 수신 시각 (epoch ms)
	private long serverTime;
}
