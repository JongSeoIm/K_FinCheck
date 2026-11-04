# AI 챗봇 연동

## 현재 코드

- 로그인한 사용자만 쓸 수 있다. 화면의 종목 대화 버튼에서 오버레이 패널로 열린다.
- 요청 흐름: 브라우저 → Spring `POST /api/chat`(로그인·대화 문맥 처리) → FastAPI `POST /chat`(질문 분류·조회·답변 생성) → OpenAI.
- 모델은 `gpt-5.4-mini`다. 질문 분류는 서버 규칙으로 하며 OpenAI가 Tool을 고르지 않는다. 분류 종류는 개념 설명, 자료 조회, 금융 무관, 정보 부족, 미지원 종목이다.
- 조회 Tool 4개는 FastAPI가 Spring API를 호출한다.
  - `get_stock_quote`: 현재가, 누적거래량, 마지막 체결 시각
  - `get_financial_result`: 연간 실적
  - `get_company_news`: 관련 기사
  - `get_company_info`: 기업 정보 (코드 안의 3종목 목록)
- 금융과 무관한 질문은 OpenAI를 호출하지 않고 고정 안내 한 문장을 돌려준다.
- 조회가 일부 실패하면 확인된 자료만 쓰고 누락을 안내한다. 전부 실패하면 OpenAI를 호출하지 않는다.
- 답변 후처리로 Markdown을 지우고 매수·매도 지시, 수익 보장 문장을 뺀다.
- 출처(`sources`)는 서버가 붙인다. 제공처, 라벨, 기준 시점, 링크로 구성된다.
- 대화 문맥은 Spring 메모리에 사용자별 최근 10개(사용자·AI 합산)를 보관한다. 새 대화, 로그아웃, 서버 재시작 시 사라진다.
- 같은 사용자의 답변 대기 중 추가 질문은 거절한다.

## 로컬 실행

`ai_server` 폴더에서 한 줄씩 실행한다.

```
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --port 8000
```

- `ai_server/.env`에 `OPENAI_API_KEY`를 넣는다. `.env`와 `.venv`는 Git에서 제외된다. 실제 값은 채팅·프론트엔드·Git에 넣지 않는다.
- `.env`를 저장한 뒤 uvicorn을 다시 시작해야 반영된다.
- 확인: `GET http://localhost:8000/health`, `GET http://localhost:8000/health/model` (`ok: true`이면 모델 호출 가능)
- 예시 파일은 `ai_server/.env.example`이다.

## 검증 상태

- 사용자 로컬에서 확인: 개념 질문 답변, 현재가 질문에 KIS 출처와 최근 체결 시각 표시, 로그인하지 않았을 때 사용 제한.
- 지원 종목은 삼성전자·SK하이닉스·NAVER 3개다.
