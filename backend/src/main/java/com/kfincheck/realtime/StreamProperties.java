package com.kfincheck.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "stream")
public class StreamProperties {
    private List<String> symbols = List.of("005930", "000660");
    private String topic = "stock-quotes-v1";
    private boolean relayEnabled = true;

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty() || symbols.size() > 2
                || symbols.stream().anyMatch(s -> !s.matches("[0-9]{6}"))) {
            throw new IllegalArgumentException("실시간 종목은 6자리 코드 1~2개로 설정하세요.");
        }
        this.symbols = symbols.stream().distinct().toList();
    }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public boolean isRelayEnabled() { return relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
}
