package com.example.stock_api.quote;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 시세 수집(1초)과 WebSocket 전송(0.5초) 스케줄을 켭니다. */
@Configuration
@EnableScheduling
public class QuoteConfig {
}
