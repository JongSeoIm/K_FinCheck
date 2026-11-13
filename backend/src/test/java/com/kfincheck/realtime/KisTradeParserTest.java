package com.kfincheck.realtime;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class KisTradeParserTest {
    static String row(String time, String price, String sign, String volume, String tradeVolume) {
        String[] fields = new String[47];
        Arrays.fill(fields, "0");
        fields[0] = "005930"; fields[1] = time; fields[2] = price; fields[3] = sign;
        fields[4] = "100"; fields[5] = "0.14"; fields[7] = "70000"; fields[8] = "71000";
        fields[9] = "69000"; fields[12] = tradeVolume; fields[13] = volume; fields[33] = "20261102";
        return String.join("^", fields);
    }

    @Test void parsesMultipleTradesAndKoreanExchangeTime() {
        var events = new KisTradeParser().parse("0|H0STCNT0|002|" + row("143000", "70100", "2", "100", "10")
                + "^" + row("143001", "69900", "5", "120", "20"));
        assertThat(events).hasSize(2);
        assertThat(events.get(1).priceChange()).isEqualTo(-100);
        assertThat(events.get(1).changeRate()).isEqualTo(-0.14);
        assertThat(events.get(0).exchangeTime()).isEqualTo(Instant.parse("2026-11-02T05:30:00Z"));
    }

    @Test void rejectsMalformedPayloadAndIgnoresOtherTransactions() {
        assertThatThrownBy(() -> new KisTradeParser().parse("0|H0STCNT0|001|bad")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new KisTradeParser().parse("1|H0STCNI0|001|encrypted")).isEmpty();
    }
}
