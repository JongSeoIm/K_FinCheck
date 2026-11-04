package com.example.stock_api.realtime;

/** KIS 실시간 체결을 다음 단계로 넘깁니다. app.kafka.enabled 값에 따라 구현이 바뀝니다. */
public interface TickPublisher {
    void publish(RealtimeMessageParser.Tick tick);
}