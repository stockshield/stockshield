# StockShield 프론트엔드

StockShield의 모의 주식 거래 화면과 이상거래 탐지(FDS) 관제 화면입니다. React 18, Vite 5, Lucide React를 사용하며 Spring Boot 백엔드와 REST API 및 STOMP WebSocket으로 연동합니다.

## 화면과 기능

| 화면 | 주요 기능 |
| --- | --- |
| 시세 | 종목 검색과 선택, KOSPI·KOSDAQ 지수, 기간별 캔들 차트, 호가, 뉴스, 위험 의심 종목 표시 |
| 회원 | 이메일 인증을 통한 회원가입, 로그인, 새 기기 로그인 시 이메일 추가 인증, 관심종목 |
| 모의 거래 | 시장가 매수·매도, 잔고와 보유종목, 주문 내역, 모의 출금 및 출금 내역 |
| 관리자 FDS | 탐지 목록·상세와 확인 처리, AI 설명 조회 및 실패 시 재요청, 시세 지연 표시, 계좌 잠금·해제, 원장 대사 실행 및 이력 조회 |

## 로컬 실행

1. Node.js 20과 npm을 준비합니다. [프론트엔드 CI](../.github/workflows/ci.yml)도 Node.js 20을 사용합니다.
2. [백엔드 실행 안내](../backend/README.md)에 따라 필요한 서비스를 시작하고 Spring Boot 서버를 `127.0.0.1:8080`에서 실행합니다. 관리자 화면은 백엔드에서 설정한 관리자 계정으로 확인합니다.
3. 저장소 루트에서 아래 명령을 실행하고 [개발 서버](http://127.0.0.1:5174)를 엽니다. 5174 포트가 사용 중이면 Vite가 시작 시 오류를 표시합니다.

```sh
cd frontend
npm ci
npm run dev
```

### 연동 설정

기본값을 사용하면 별도 환경 파일 없이 실행할 수 있습니다. 주소를 바꿀 때는 [`.env.example`](.env.example)을 `.env.local`로 복사해 설정한 뒤 개발 서버를 다시 시작합니다.

| 환경 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api/v1` | REST API 기본 경로 |
| `VITE_WS_URL` | `/ws` | STOMP WebSocket 접속 경로 |

[개발 서버 설정](vite.config.js)은 `/api`와 `/ws` 요청을 8080 포트의 백엔드로 전달합니다. 초기 시세와 종목 상세는 REST로 조회하고, 순위·위험 종목·선택 종목 체결 데이터는 WebSocket으로 갱신합니다. 관리자 화면은 탐지·시세 지연·원장 대사 이벤트도 구독합니다. 호가는 선택 종목 기준으로 3초마다 REST로 다시 조회합니다.

## 빌드와 배포

`frontend/`에서 실행합니다.

```sh
npm run build
npm run preview
```

빌드 결과는 `dist/`에 생성됩니다. `preview`는 빌드 결과를 로컬에서 확인할 때 사용합니다. 배포에서는 Nginx가 정적 파일을 제공하고 같은 주소의 `/api`와 `/ws` 요청을 백엔드로 전달합니다. 배포 절차는 [인프라 안내](../infra/README.md)를 참고하세요.

## 코드 위치

| 경로 | 역할 |
| --- | --- |
| [`src/main.jsx`](src/main.jsx) | 거래 화면, 시장 데이터 연결, 캔들 차트, 로그인 흐름 |
| [`src/components/`](src/components/) | 주문·출금·회원가입·뉴스 및 관리자 FDS 화면 |
| [`src/utils/storage.js`](src/utils/storage.js) | REST 요청, 토큰 갱신, 브라우저 저장소, API 응답 변환 |
| [`src/utils/stompClient.js`](src/utils/stompClient.js) | STOMP 연결, 구독, 재연결 |
| [`src/styles.css`](src/styles.css) | 화면 스타일과 반응형 레이아웃 |
