package com.example.stock_api.realtime;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** stream.* 설정. 실시간 체결 구독은 KIS 동시 구독 한도 때문에 1~2종목으로 제한합니다. */
@ConfigurationProperties(prefix = "stream")
public class RealtimeProperties {

    private boolean enabled = false;
    private boolean relayEnabled = true;
    private List<String> symbols = List.of("005930", "000660");
    private String topic = "stock-quotes-v1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty() || symbols.size() > 2
                || symbols.stream().anyMatch(s -> !s.matches("[0-9]{6}"))) {
            throw new IllegalArgumentException("실시간 종목은 6자리 코드 1~2개로 설정하세요.");
        }
        this.symbols = symbols.stream().distinct().toList();
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
