# K_FinCheck AI 서버 (FastAPI)

금융 챗봇의 질문 분류, 조회 Tool 호출, OpenAI 답변 생성을 담당합니다.
브라우저는 이 서버를 직접 호출하지 않고, Spring(`/api/chat`)이 로그인 확인 후 호출합니다.

## 처리 흐름
1. 규칙으로 질문 분류: 금융 무관 / 용어 설명 / 조회 필요 / 조건 부족 / 미지원 종목
2. 필요한 Tool만 Spring에서 조회: 현재가, 실적, 관련 기사
3. 조회 결과만 OpenAI(GPT-5.4-mini)에 전달해 답변 생성. 금융 무관 질문과 조회 전부 실패는 OpenAI를 호출하지 않음
4. 답변 후처리(Markdown 제거, 매수·매도 지시 문장 제거) 후 `sources`를 서버가 붙여 반환

## 실행 (Windows PowerShell, ai_server 폴더에서)
```
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
```
`.env`의 `OPENAI_API_KEY=` 뒤에 키를 입력한 뒤 실행합니다. (`.env`는 git에 올리지 않습니다)
```
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

## 확인
- 서버 상태: http://127.0.0.1:8000/health
- 키·모델 확인: `Invoke-RestMethod http://127.0.0.1:8000/health/model` → `ok: True` 이면 사용 가능
- 테스트: `python -m pytest -q`

## 응답 형식
`status`: `answered`, `out_of_scope`, `need_more_info`, `unsupported`, `no_data`, `error`
`sources`: `{provider, label, basisAt, url}` 목록
