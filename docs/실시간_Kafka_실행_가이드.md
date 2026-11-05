# 실시간 체결(Kafka) 실행 가이드

## 구현 흐름

KIS 실시간 체결(H0STCNT0) → 메시지 해석 → Kafka(`stock-quotes-v1`) → 소비 → `QuoteStore` → 화면의 Polling/Push.

- 실시간 구독 종목은 삼성전자 005930, SK하이닉스 000660 두 종목이다. KIS 동시 구독 한도 때문에 1~2종목으로 제한한다.
- NAVER(035420)와 장 외 시간은 기존 수집기(`QuoteCollector`, KIS 현재가 조회)가 맡는다. 체결이 15초 안에 들어오는 종목은 수집기가 건너뛰고, 체결이 끊기면 수집기가 다시 읽는다.
- KIS의 공용 시세만 구독한다. 개인 주문·계좌 체결통보와 주문 API는 쓰지 않는다.
- REST 접근토큰과 별도로 `/oauth2/Approval` 접속키를 서버가 발급해 메모리에서 재사용한다(약 23시간). 키·접속키·원본 응답은 화면이나 로그로 내보내지 않는다.
- 화면(Polling 2초, Push 0.5초)과 챗봇은 그대로이며, 체결이 들어오면 `QuoteStore` 값이 체결 단위로 바뀐다.
- 초·분봉용 샘플도 체결마다 쌓는다. 값이 바뀐 시점에만 쌓는 규칙은 그대로다.

## 준비

1. `backend/pom.xml`의 `<dependencies>`에 추가한다. 버전은 Spring Boot가 관리하므로 적지 않는다.

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

2. `application-local.properties`에 `kis.app-key`, `kis.app-secret`이 이미 있으면 그대로 쓴다. 추가 설정은 없다.

## Docker 없이 로컬 실행

기존 8080 백엔드를 종료하고 `backend` 폴더에서 한 줄로 실행한다.

```powershell
mvn spring-boot:run "-Dspring-boot.run.useTestClasspath=true" "-Dspring-boot.run.main-class=com.example.stock_api.realtime.LocalRealtimeApplication"
```

- 평소 `mvnw`를 쓰면 `mvn` 대신 `.\mvnw.cmd`를 쓴다.
- 같은 프로세스 안에 개발용 Kafka(KRaft)가 뜨고, `stream.enabled=true`로 백엔드가 실행된다. Ctrl+C로 종료하면 Kafka 내용은 사라진다.
- 기존처럼 실행하면(`stream.enabled` 없음) 실시간 기능은 꺼지고 기존 수집기만 동작한다.
- KIS 실시간 연결은 앱키당 1개가 기본이다. 같은 앱키로 백엔드를 두 개 띄우지 않는다.

## 확인

- 상태: `http://localhost:8080/api/realtime/status`
  - `relay`: `CONNECTING` → `SUBSCRIBING` → `WAITING_TRADE` → `RECEIVING`
  - `kafka`: 체결이 소비되면 `READY`
  - `lastTick`: 종목별 마지막 체결 수신 시각
- 장중에 화면에서 삼성전자·SK하이닉스 현재가와 누적거래량이 체결 단위로 바뀌는지, 5초봉이 촘촘해졌는지 본다.
- 상태 문구
  - `CONFIG_REQUIRED`: 앱키·시크릿이 비어 있다.
  - `WAITING_KAFKA`: Kafka가 아직 준비되지 않았다. 5초 간격으로 다시 시도한다.
  - `SUBSCRIPTION_ERROR`: KIS가 구독을 거절했다(접속키·한도). 접속키를 다시 발급한다.
  - `RECONNECTING`: 연결이 끊겨 재시도 중이다. 90초 동안 아무 메시지가 없어도 재연결한다.
  - `DATA_ERROR`: 체결 메시지 해석 실패. 연결을 끊고 다시 연결한다.
