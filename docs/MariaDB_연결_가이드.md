# MariaDB 연결 가이드

## 구성

- 현재 백엔드 Spring Boot 3.4.3 / Java 21을 유지한다.
- 강사님 backend_hkboard_springboot_restAPI12 예제와 동일하게 MyBatis Starter 3.0.5와 MariaDB JDBC 드라이버를 사용한다. 드라이버 버전은 Spring Boot 의존성 관리에 따른다.
- 기본 주소는 `127.0.0.1:3306`, DB는 `k_fincheck`, 계정은 `k_fincheck_app`이다.
- 접속 정보는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 설정하거나 Git 제외 로컬 설정으로 지정할 수 있다.
- 기존 시세·실적·산업 정책 설정은 유지한다. SQL 자동 초기화는 꺼져 있어 실행 시 테이블을 생성·변경·삭제하지 않는다.

## 로컬 비밀번호 입력

`backend/src/main/resources/application-local.yml` 맨 아래의 DB 설정에서 입력용 문구를 실제 비밀번호로 바꾼다. 이미 추가한 spring 블록을 수정하며 동일한 블록을 중복 추가하지 않는다.

```yaml
spring:
  datasource:
    username: k_fincheck_app
    password: "YOUR_DB_PASSWORD"
```

실제 비밀번호를 Git·채팅·로그·프론트엔드에 넣지 않는다. 이 파일을 공유 설정 예시 파일로 덮어쓰면 기존 API 키를 잃을 수 있으므로 필요한 항목만 수정한다.

## 실행·연결 확인

비밀번호 저장 후 기존 백엔드를 재시작한다. 실시간 기능은 기존 `start-realtime.ps1` 실행 방식을 유지한다.

`GET http://localhost:8080/api/health/db`

정상 응답:

```json
{"status":"UP","database":"k_fincheck","queryResult":1}
```

MyBatis Mapper(interface + XML)가 `SELECT 1`과 `SELECT DATABASE()`를 실행한다. 다른 DB로 연결되거나 오류가 발생하면 HTTP 503과 일반 안내만 반환한다. 기존 `/api/health`는 서버 상태만 확인하며 DB 연결 성공을 의미하지 않는다.

DB 풀은 최대 5개이며 사용 전 연결을 미리 열지 않는다. DB에 연결할 수 없어도 애플리케이션 시작과 DB를 사용하지 않는 기존 기능을 유지하도록 설정했다. 확인 API로 실제 연결 상태를 별도 확인한다.

## 검증 상태

- DB 확인 API의 정상·다른 스키마·예외 정보 숨김 처리와 기존 헬스체크·시세 컨트롤러·Kafka/WebSocket 통합 관련 테스트 총 6개 통과.
- POM·Mapper XML 구조 및 공백 검사 통과.
- 사용자 로컬 비밀번호 설정 후 별도 검증 서버에서 실제 MariaDB에 접속했다. MyBatis SELECT 1 결과 1, SELECT DATABASE() 결과 k_fincheck, /api/health/db HTTP 200·UP을 확인했다. 기존 /api/health도 UP이었다.
- 이번 검증은 연결 확인만 수행했으며 테이블에 데이터를 추가·수정·삭제하지 않았다.
- 회원가입·관심 종목·기업·실적의 DB 저장 로직은 이번 연결 준비 범위에 포함하지 않았다.

## 참고

- [강사님 백엔드 예제](https://github.com/kang99king3/back_end_edu_20260629/tree/main/web_edu_20260910/backend_hkboard_springboot_restAPI12)
- [MyBatis Spring Boot Starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)
