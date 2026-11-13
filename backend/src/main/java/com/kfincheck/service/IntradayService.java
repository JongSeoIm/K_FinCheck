package com.kfincheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kfincheck.client.KisApiClient;
import com.kfincheck.config.KisConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class IntradayService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final KisApiClient authentication;
    private final KisConfig config;
    private final RestClient client;
    private final Map<String, Cached> cache = new HashMap<>();
    private record Cached(Page page, Instant expires) { }
    public record Bar(String date, long open, long high, long low, long close, long volume) { }
    public record Page(List<Bar> candles, String nextBefore, boolean hasMore, String source, String fetchedAt) { }

    @org.springframework.beans.factory.annotation.Autowired
    public IntradayService(KisApiClient authentication, KisConfig config, RestClient.Builder builder) {
        this(authentication,config,configured(config,builder));
    }
    IntradayService(KisApiClient authentication, KisConfig config, RestClient client) {
        this.authentication=authentication; this.config=config; this.client=client;
    }
    private static RestClient configured(KisConfig config,RestClient.Builder builder) {
        var factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000); factory.setReadTimeout(10000);
        return builder.baseUrl(config.getBaseUrl()).requestFactory(factory).build();
    }

    public synchronized Page history(String symbol, String before) {
        if (symbol == null || !symbol.matches("\\d{6}")) throw new IllegalArgumentException("종목 코드는 숫자 6자리로 입력해 주세요.");
        Instant now=Instant.now();
        Instant cursor;
        try { cursor=before==null || before.isBlank() ? now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(1) : Instant.parse(before); }
        catch (DateTimeException e) { throw new IllegalArgumentException("분봉 조회 시각이 올바르지 않습니다."); }
        if (cursor.isAfter(now)) throw new IllegalArgumentException("미래 시각의 분봉은 조회할 수 없습니다.");
        Instant minimum=now.atZone(SEOUL).minusYears(1).toInstant();
        if (cursor.isBefore(minimum)) return new Page(List.of(),null,false,"KIS",now.toString());
        String key=symbol+":"+cursor.truncatedTo(ChronoUnit.MINUTES);
        var old=cache.get(key); if(old!=null && old.expires.isAfter(now)) return old.page;
        var bars=new TreeMap<Instant,Bar>();
        int pages=0, attempts=0;
        try {
            String token=authentication.getAccessToken();
            while (pages<3 && attempts++<10 && !cursor.isBefore(minimum)) {
                var dateTime=cursor.atZone(SEOUL);
                if(dateTime.getHour()<9) { cursor=dateTime.toLocalDate().minusDays(1).atTime(15,30).atZone(SEOUL).toInstant(); continue; }
                if(dateTime.toLocalTime().isAfter(LocalTime.of(15,30))) cursor=dateTime.toLocalDate().atTime(15,30).atZone(SEOUL).toInstant();
                var requestTime=cursor.atZone(SEOUL).withSecond(0).withNano(0);
                JsonNode response=null;
                for(int retry=0;retry<3;retry++) {
                 Thread.sleep(700);
                 authentication.paceRequest();
                 try {
                  response=client.get().uri(uri->uri.path("/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE","J").queryParam("FID_INPUT_ISCD",symbol)
                        .queryParam("FID_INPUT_DATE_1",requestTime.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                        .queryParam("FID_INPUT_HOUR_1",requestTime.format(DateTimeFormatter.ofPattern("HHmmss")))
                        .queryParam("FID_PW_DATA_INCU_YN","Y").queryParam("FID_FAKE_TICK_INCU_YN","N").build())
                        .header("authorization","Bearer "+token).header("appkey",config.getAppKey()).header("appsecret",config.getAppSecret())
                        .header("tr_id","FHKST03010230").header("custtype","P").retrieve().body(JsonNode.class);
                  break;
                 } catch(org.springframework.web.client.RestClientResponseException e) {
                  String code;
                  try {code=new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString()).path("msg_cd").asText();}
                  catch(Exception ignored){throw e;}
                  if(!"EGW00201".equals(code) || retry==2) throw e;
                  Thread.sleep(1000L*(retry+1));
                 }
                }
                if(response==null || !"0".equals(response.path("rt_cd").asText()) || !response.path("output2").isArray()) {
                    org.slf4j.LoggerFactory.getLogger(IntradayService.class).warn("KIS 분봉 응답 코드: {}",response==null?"EMPTY":response.path("msg_cd").asText("UNKNOWN"));
                    throw new IllegalStateException();
                }
                var parsed=parse(response.path("output2"),cursor,minimum);
                if(parsed.isEmpty()) { cursor=requestTime.toLocalDate().minusDays(1).atTime(15,30).atZone(SEOUL).toInstant(); continue; }
                for(var bar:parsed) bars.put(Instant.parse(bar.date()),bar);
                Instant oldest=Instant.parse(parsed.getFirst().date());
                cursor=oldest.minusSeconds(1);pages++;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("분봉 조회가 중단됐습니다."); }
        catch(Exception e) {
            if(e instanceof org.springframework.web.client.RestClientResponseException responseError) {
                try {
                    var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseError.getResponseBodyAsString());
                    org.slf4j.LoggerFactory.getLogger(IntradayService.class).warn("KIS 분봉 HTTP 응답 코드: {}",body.path("msg_cd").asText("UNKNOWN"));
                } catch(Exception ignored) { }
            }
            org.slf4j.LoggerFactory.getLogger(IntradayService.class).warn("분봉 조회 실패 유형: {}",e.getClass().getSimpleName());
            throw new IllegalStateException("과거 분봉을 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        boolean more=!cursor.isBefore(minimum) && !bars.isEmpty();
        var result=new Page(List.copyOf(bars.values()),more?cursor.toString():null,more,"KIS",now.toString());
        cache.entrySet().removeIf(e->e.getValue().expires.isBefore(now));
        if(cache.size()>200) cache.clear();
        cache.put(key,new Cached(result,now.plusSeconds(60)));
        return result;
    }

    static List<Bar> parse(JsonNode rows, Instant before, Instant minimum) {
        var result=new TreeMap<Instant,Bar>();
        for(var row:rows) try {
            var day=LocalDate.parse(row.path("stck_bsop_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
            var time=LocalTime.parse(row.path("stck_cntg_hour").asText(),DateTimeFormatter.ofPattern("HHmmss"));
            var at=day.atTime(time).atZone(SEOUL).toInstant();
            if(at.isAfter(before) || at.isBefore(minimum) || time.isBefore(LocalTime.of(9,0)) || time.isAfter(LocalTime.of(15,30))) continue;
            long open=Long.parseLong(row.path("stck_oprc").asText()), high=Long.parseLong(row.path("stck_hgpr").asText());
            long low=Long.parseLong(row.path("stck_lwpr").asText()), close=Long.parseLong(row.path("stck_prpr").asText());
            long volume=Long.parseLong(row.path("cntg_vol").asText());
            if(open<=0 || low<=0 || volume<0 || high<Math.max(open,close) || low>Math.min(open,close)) continue;
            result.put(at,new Bar(at.toString(),open,high,low,close,volume));
        } catch (DateTimeException | NumberFormatException ignored) { }
        return List.copyOf(result.values());
    }
}
