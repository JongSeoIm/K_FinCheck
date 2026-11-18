# 조선비즈 RSS 뉴스 연동

네이버 API HUB 뉴스 검색을 없애고 조선비즈 공식 RSS로 바꿨다. 기사 본문은 저장하지 않고, 사용자가 요청한 기사만 AI로 요약한다.

## 이용 조건 (2026-11-13 확인)

- 조선비즈 robots.txt: `User-agent: *` 허용, `Content-Signal: search=yes, ai-input=yes, ai-train=no`
- 출처 표시·원문 링크 조건으로 검색 노출·인용 허용. AI 학습용 데이터셋·보관용 사본은 금지
- AI 요약 직전에 robots.txt를 다시 확인한다(10분 이내 결과는 재사용). `ai-input=yes`가 아니면 요약을 멈추고 버튼을 숨긴 뒤 안내 문구를 보여 준다.
- 확인 결과는 AI 서버 `GET /api/news/terms`에서 볼 수 있다.

## 전체 흐름

```
조선비즈 RSS 4개 ─(30분마다)→ AI 서버 메모리(최근 5일)
                                  │
         종목 사전으로 제목 필터 ←─┘
                │
 Spring GET /api/stocks/{code}/news ─→ 뉴스 화면(관련 기사 목록)
                                     └→ AI 대화 "관련 기사 알려줘"(앞 5건)
 Spring POST /api/stocks/{code}/news/summary ─→ AI 요약(버튼·AI 대화)
                │
        MariaDB news_summary (24시간 보관)
```

## 1. 수집 (`ai_server/news/collector.py`, `http.py`, `rss.py`)

- 피드 4개: 증권, 산업, IT과학, 전체 (`ai_server/news/terms.py`)
- 30분마다, 피드당 최대 100건. 일본어·영어판 기사는 제외
- 같은 사이트 요청 간격 5초, 1회 최대 30요청, 429·5xx는 간격을 늘려 최대 3회 재시도, ETag·If-Modified-Since 사용
- 제목·링크·발행 시각·출처만 보관한다. RSS에 들어 있는 본문·이미지는 버린다.
- `NEWS_COLLECTOR_ENABLED=true`일 때만 동작한다(기본값 꺼짐). `ai_server/.env.example` 참고

## 2. 보관 (`ai_server/news/store.py`)

- 서버 메모리에만 둔다(DB·파일 저장 없음). 서버를 다시 켜면 비워지고 다음 수집부터 다시 쌓인다.
- 발행 후 5일이 지난 기사는 받지 않고, 보관 중이면 지운다. 최대 3000건, 같은 링크는 한 번만 보관한다.

## 3. 종목별 필터 (`ai_server/news/dictionary.py`, `classify.py`)

종목마다 종목명·별칭, 산업 키워드, 제외 단어를 정해 두고 **기사 제목**에서 찾는다.

1. 제외 단어를 먼저 지운다(예: 삼성전자서비스, 기아 타이거즈, 현대차증권).
2. 종목명·별칭이 있으면 **직접 관련**
3. 산업 키워드만 있으면 **산업 관련**(예: 삼성전자 화면의 "반도체" 기사)
4. 둘 다 없으면 보여 주지 않는다.

- 영문 별칭은 단어 단위로만 찾는다("Nokia" 안의 "kia"는 기아가 아님). 한글은 포함 여부로 찾는다.
- 본문 분석이 아닌 제목 키워드 기준이며, 화면에 이 기준을 안내한다.
- 산업 키워드가 겹치면 같은 기사가 여러 종목에 나온다(예: HBM 기사는 삼성전자·SK하이닉스 모두).

## 4. 목록 API

- AI 서버 `GET /api/news/feed?stock={code}`: 보관 중인 기사 전체에서 먼저 거른 뒤 최신순 최대 50건
- Spring `GET /api/stocks/{code}/news`: AI 서버 결과를 60초 캐시하고 https 링크만 전달
- 응답: 제목, 링크, 출처, 발행 시각, 관련도(직접·산업), 기간(5일), 요약 가능 여부, 기준 안내 문구
- 지원 종목 8개(삼성전자, SK하이닉스, NAVER, 카카오, 현대차, 기아, LG화학, 삼성SDI) 외에는 404 `UNSUPPORTED_STOCK`

## 5. 화면 (`stock_dashboard/src/app/components/NewsFeed.jsx`)

- "{종목} 관련 기사 N건 · 최근 5일 · 최신순 · 조선비즈 공식 RSS"
- 기사마다 직접 관련·산업 관련 표시, 원문 새 탭 링크, 'AI 요약' 버튼
- 관련 기사가 없으면 안내 문구를 보여 준다.

## 6. AI 요약 (`ai_server/news/summary.py`, Spring `NewsService`)

요청 방법(둘 다 로그인 필요, 비로그인은 401 `LOGIN_REQUIRED`):

- 뉴스 목록의 'AI 요약' 버튼 → `POST /api/stocks/{code}/news/summary`
- AI 대화: 기사 링크, 따옴표로 감싼 제목 일부(`'노조 협상' 기사 요약해줘`), 대화 속 목록 번호(`1번 기사 요약해줘`) — `chat/ArticleSummaryChat.java`

처리 순서:

1. 요청한 기사가 해당 종목 목록에 있는지 확인
2. DB에 24시간 안의 요약이 있으면 그대로 사용(OpenAI 재호출 없음)
3. robots.txt 재확인 → 원문을 1회 가져와 본문 최대 6000자로 2~3문장 요약(OpenAI Responses API, `store=False`, 최대 출력 400토큰) → 본문은 바로 버림
4. 요약문을 MariaDB `news_summary`에 24시간 보관(`backend/src/main/resources/db/news-summary.sql`, 백엔드 시작 시 자동 생성)

제한: 사용자당 하루 20건(버튼·대화 합산), 서버 전체 시간당 60건

## 7. AI 대화 기록 보관

- 대화 기록(`chat_message`)에는 요약문을 넣지 않고 기사 제목·링크만 남긴다. 대화를 다시 열 때 DB에 요약이 남아 있으면 채우고, 지났으면 만료로 표시한다.
- 대화 기록 속 기사 제목·뉴스 답변도 24시간이 지나면 지운다(`ChatHistory.scrubExpiredNews`). 대화를 열 때와 10분마다(`NewsRetentionJob`) 처리한다.
- AI 대화 "관련 기사 알려줘"는 뉴스 화면과 같은 목록의 앞 5건을 같은 순서로 보여 준다(`ai_server/app/news_tool.py`).

## 설정

- `ai_server/.env`: `OPENAI_API_KEY`, `OPENAI_MODEL`, `NEWS_COLLECTOR_ENABLED=true`
- Spring: 기존 MariaDB 설정 그대로 사용. 네이버 `naver-news` 설정은 더 이상 필요 없다.
- Spring → AI 서버 읽기 제한 시간 30초(`application.yml`의 `ai-server.read-timeout`)

## 검증 (2026-11-15)

- Python 테스트 74개, Spring 테스트 83개, 프론트엔드 ESLint·프로덕션 빌드 통과
- 실제 서버: 기사 167건 수집, 8개 종목 관련 기사 목록 표시, AI 대화 관련 기사 5건, 번호·따옴표 제목으로 요약(4~5초), 같은 기사 재요청 시 DB 보관분 사용(1초), 관련 기사 0건 안내 확인

## 남은 일

- 요약이 30초를 넘으면 Spring 쪽에서 시간 초과가 날 수 있다(테스트에서는 4~5초).
- 조선미디어 이용약관 원문은 아직 확인하지 못했다.
