# StockShield AI 서버 (ai-server)

FastAPI + Google Gemini API(무료 등급). Python 3.12.
메인 서버만 부르는 내부 API이고, 바깥에는 열지 않습니다. 포트는 **8000**입니다.

| API | 하는 일 | 부르는 곳 |
|---|---|---|
| `POST /api/v1/internal/ai/summary` | 뉴스 기사 → 3문장 이내 요약(`aiSmryCont`), 감성 점수 -1.00~1.00(`aiSntmScr`) | 메인 서버 뉴스 수집 배치 (NEWS_AI_001) |
| `POST /api/v1/internal/ai/detection-reason` | FDS 탐지 기록 → 사유 설명 2~3문장(`aiRsnCont`), 확인할 점(`aiChkCont`) | 메인 서버 탐지 저장 직후, 관리자 재요청 (FDS_AI_001) |
| `GET /health` | 상태, 모델 이름, 키 설정 여부 (키 값은 내보내지 않음) | 점검용 |

- 판단은 FDS 규칙이 하고, AI는 이유를 쉬운 말로 정리만 합니다.
- **개인정보:** 회원번호·아이디·계좌번호·IP·기기·요청 해시는 메인 서버가 빼고 보냅니다. AI 서버도 같은 이름의 키를 한 번 더 지웁니다.
- **실패:** AI를 쓸 수 없으면(키 없음·한도·시간 초과·해석 실패) `500 {"code":"AI_UNAVAILABLE"}`로 응답합니다. 메인 서버는 뉴스는 다음 배치에서 다시 요청하고, 탐지 설명은 `FAILED`로 저장합니다(관리자 재요청 가능).
- **시간 제한:** AI 서버는 Gemini 응답을 8초까지만 기다립니다(`GEMINI_TIMEOUT_MS`). 메인 서버의 10초 제한 안에 응답하기 위해서입니다. Gemini 요청 자체의 마감은 API 최소값인 10초입니다.

## 설정

환경 변수를 먼저 읽고, 없으면 저장소의 `infra/.env`를 읽습니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `GEMINI_API_KEY` | (없음) | Google AI Studio에서 발급. 절대 커밋하지 않습니다. 결제(유료 등급)는 켜지 않습니다 |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` | 응답이 빨라 메인 서버의 10초 제한 안에 들어옴. 더 큰 flash 모델은 11/13 확인 때 13초 넘게 걸려 시간 초과 |
| `GEMINI_TIMEOUT_MS` | `8000` | AI 서버가 기다리는 시간(밀리초). 포트 번호와 관계없음 |

메인 서버는 `AI_SERVER_URL`로 AI 서버를 찾습니다.

| 실행 위치 | `AI_SERVER_URL` |
|---|---|
| 내 PC (메인 서버를 `bootRun`으로 실행) | `http://localhost:8000` (기본값, `infra/.env.example`과 같음) |
| 배포 (`docker-compose.prod.yml`) | `http://ai-server:8000` (compose가 넣으므로 `.env`에 쓰지 않음) |

## 실행 (내 PC)

```powershell
cd ai-server
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt -r requirements-dev.txt
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

확인: http://127.0.0.1:8000/health 에서 `"status":"UP"`, `"keyConfigured":true`가 나오면 됩니다.

## 배포

`infra/docker-compose.prod.yml`의 `ai-server` 서비스가 `Dockerfile`로 빌드합니다(python:3.12.10-slim, 일반 사용자로 실행).
포트는 docker 네트워크 안에만 열려 있고 메인 서버만 부릅니다. 자세한 배포 방법은 `infra/README.md`를 봅니다.

## 구조

| 파일 | 내용 |
|---|---|
| `app/main.py` | API 3개, 오류 응답(`AI_UNAVAILABLE`) |
| `app/schemas.py` | 요청·응답 형식 |
| `app/prompts.py` | Gemini에 보내는 지시문, 개인정보 키 제거(`scrub`) |
| `app/llm.py` | Gemini 호출, 시간 제한, 응답 해석 |
| `app/config.py` | 설정 읽기 |

## 테스트

Gemini를 부르지 않고 가짜 응답으로 확인합니다. GitHub Actions(`ci.yml`)도 같은 테스트를 돌립니다.

```powershell
.\.venv\Scripts\python -m pytest -q
```
