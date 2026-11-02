package com.kfincheck.realtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QuoteStoreTest {
    @Test void aggregatesObservedOhlcAndRejectsDuplicatesAndOlderTrades() {
        var parser = new KisTradeParser();
        var store = new QuoteStore();
        var first = parser.parse("0|H0STCNT0|001|" + KisTradeParserTest.row("143001", "70100", "2", "100", "10")).getFirst();
        assertThat(store.accept(first)).isTrue();
        assertThat(store.accept(first)).isFalse();
        assertThat(store.accept(parser.parse("0|H0STCNT0|001|" + KisTradeParserTest.row("143002", "70300", "2", "120", "20")).getFirst())).isTrue();
        assertThat(store.accept(parser.parse("0|H0STCNT0|001|" + KisTradeParserTest.row("143003", "69900", "5", "130", "10")).getFirst())).isTrue();
        assertThat(store.accept(parser.parse("0|H0STCNT0|001|" + KisTradeParserTest.row("143000", "71000", "2", "90", "10")).getFirst())).isFalse();
        var snapshot = store.snapshot(60, List.of("005930"));
        @SuppressWarnings("unchecked")
        var bars = (Map<String, List<QuoteStore.ObservedCandle>>) snapshot.get("candles");
        assertThat(bars.get("005930")).hasSize(1);
        var bar = bars.get("005930").getFirst();
        assertThat(bar.open()).isEqualTo(70100);
        assertThat(bar.high()).isEqualTo(70300);
        assertThat(bar.low()).isEqualTo(69900);
        assertThat(bar.close()).isEqualTo(69900);
        assertThat(bar.volume()).isEqualTo(40);
        assertThatThrownBy(() -> store.snapshot(7, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
