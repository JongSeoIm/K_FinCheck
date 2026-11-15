package com.kfincheck.realtime;

import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class KisTradeParser {
    public List<QuoteEvent> parse(String frame) {
        // Market executions only. Encrypted personal execution notices are not subscribed.
        String[] parts = frame.split("\\|", 4);
        if (parts.length != 4 || !"0".equals(parts[0]) || !"H0STCNT0".equals(parts[1])) return List.of();
        int count = Integer.parseInt(parts[2]);
        String[] fields = parts[3].split("\\^", -1);
        if (count < 1 || count > 1000 || fields.length % count != 0 || fields.length / count < 34) {
            throw new IllegalArgumentException("실시간 체결 메시지 형식 오류");
        }
        int width = fields.length / count;
        List<QuoteEvent> result = new ArrayList<>();
        for (int row = 0; row < count; row++) {
            int i = row * width;
            String symbol = fields[i];
            if (!symbol.matches("[0-9]{6}")) throw new IllegalArgumentException("종목코드 형식 오류");
            LocalDate date = LocalDate.parse(fields[i + 33], DateTimeFormatter.BASIC_ISO_DATE);
            LocalTime time = LocalTime.parse(fields[i + 1], DateTimeFormatter.ofPattern("HHmmss"));
            long price = Long.parseLong(fields[i + 2]);
            long volume = Long.parseLong(fields[i + 13]);
            long tradeVolume = Long.parseLong(fields[i + 12]);
            if (price <= 0 || volume < 0 || tradeVolume < 0) throw new IllegalArgumentException("체결 수치 오류");
            String sign = fields[i + 3];
            int direction = ("4".equals(sign) || "5".equals(sign)) ? -1 : "3".equals(sign) ? 0 : 1;
            result.add(new QuoteEvent(symbol, price, direction * Math.abs(Long.parseLong(fields[i + 4])),
                    direction * Math.abs(Double.parseDouble(fields[i + 5])),
                    Long.parseLong(fields[i + 7]), Long.parseLong(fields[i + 8]), Long.parseLong(fields[i + 9]),
                    volume, tradeVolume, date.toString(), time.toString(),
                    date.atTime(time).atZone(ZoneId.of("Asia/Seoul")).toInstant(), Instant.now(), "KIS_WEBSOCKET_KRX"));
        }
        return result;
    }
}
