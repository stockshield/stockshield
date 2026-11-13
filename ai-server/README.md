# StockShield AI 서버 (ai-server)

FastAPI + Google Gemini API(무료 등급). 메인 서버만 부르는 내부 API입니다(외부에 열지 않음).

| API | 하는 일 | 근거 |
|---|---|---|
| `POST /api/v1/internal/ai/summary` | 뉴스 기사 → 3문장 이내 요약(`aiSmryCont`), 감성 점수 -1.00~1.00(`aiSntmScr`) | NEWS_AI_001, API 명세서 15행 |
| `POST /api/v1/internal/ai/detection-reason` | FDS 탐지 기록 → 사유 설명 2~3문장(`aiRsnCont`), 확인할 점(`aiChkCont`) | FDS_AI_001, API 명세서 16행 |
| `GET /health` | 상태, 모델 이름, 키 설정 여부 (키 값은 내보내지 않음) | |

- 판단은 FDS 규칙이 하고, AI는 이유를 쉬운 말로 정리만 합니다.
- **개인정보:** 회원번호·아이디·계좌번호·IP·기기·요청 해시는 메인 서버가 빼고 보냅니다. AI 서버도 같은 이름의 키를 한 번 더 지웁니다.
- **실패:** AI를 쓸 수 없으면(키 없음·한도·시간 초과·해석 실패) `500 {"code":"AI_UNAVAILABLE"}`로 응답합니다. 메인 서버는 뉴스는 다음 배치에서 다시 요청하고, 탐지 설명은 `FAILED`로 저장합니다(관리자 재요청 가능).
- **시간 제한:** AI 서버는 Gemini 응답을 8초까지만 기다립니다(`GEMINI_TIMEOUT_MS`). 메인 서버의 10초 제한 안에 응답하기 위해서입니다. Gemini 요청 자체의 마감은 API 최소값인 10초입니다.

## 설정

`infra/.env`(또는 환경 변수)에서 읽습니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `GEMINI_API_KEY` | (없음) | Google AI Studio에서 발급. 절대 커밋하지 않습니다 |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` | 응답이 빨라 메인 서버의 10초 제한 안에 들어옴. 더 큰 flash 모델은 11/13 확인 때 13초 넘게 걸려 시간 초과 |
| `GEMINI_TIMEOUT_MS` | `8000` | AI 서버가 기다리는 시간. Gemini 요청 마감은 API 최소값인 10초로 두고 자동 재시도는 끔 |

## 실행

```powershell
cd ai-server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt -r requirements-dev.txt
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

## 테스트

Gemini를 부르지 않고 가짜 응답으로 확인합니다.

```powershell
.\.venv\Scripts\python -m pytest -q
```
