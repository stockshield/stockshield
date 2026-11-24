# StockShield 메인 서버 (backend)

회원가입과 로그인, 주문, 원장 반영, 모의 출금, 원장 대사, 관리자 API를 처리하는 Spring Boot 서버다. FDS 판단, 시세, 뉴스 코드도 같은 앱 안의 별도 패키지로 들어 있다. 패키지별 담당은 아래 [패키지 구조](#패키지-구조)에 있다.

Java 17 · Spring Boot 3.3.5 · Spring Security(JWT) · Spring Data JPA · PostgreSQL 15.19 · Redis 7.2.17 · Flyway · Testcontainers 1.21.4

## 원장 설계

고객의 돈과 주식 수량이 바뀌는 곳에서 지킨 규칙이다.

| 규칙 | 구현 | 막는 사고 |
|---|---|---|
| 계좌 행 하나만 잠금 | `SELECT … FOR NO KEY UPDATE`, 대기 2초 | 동시 주문과 출금이 같은 잔고를 두 번 쓰는 것 |
| `FOR UPDATE` 대신 `FOR NO KEY UPDATE` | 외래 키 확인 락과 충돌하지 않음 | 대사 결과를 저장하는 동안 주문과 출금이 멈추는 것 |
| 한 요청은 한 커밋 | 원장, 주문(출금), 내역을 함께 커밋 | 돈은 빠졌는데 내역이 없는 부분 실패 |
| 업무 거절도 결과로 저장 | `REJECTED`와 사유를 커밋하고 시스템 오류만 롤백 | 거절 이력과 비밀번호 오류 횟수가 롤백으로 사라지는 것 |
| 멱등키 이중 확인 | 계좌 락 안에서 조회, 주문은 락 밖에서도 조회, DB UNIQUE | 같은 주문이나 출금이 두 번 처리되는 것 |
| 기록 수정 불가 | 앱 DB 계정에 UPDATE, DELETE 권한 없음 | 버그나 실수로 거래 기록이 바뀌는 것 |
| 판단과 기록에 같은 시계 | `Clock` 빈으로 판단하고 `reg_ts`도 기록 | 판단 시각과 기록 시각이 어긋나는 것 |
| 거래추적번호 | 로그, 응답 헤더, 에러 본문, 거래 행에 같은 UUID | 문의가 왔을 때 로그와 거래 기록을 따로 찾는 것 |

계좌 락은 `ledger.AccountLocker`의 네이티브 쿼리로 잡는다. `lock_timeout` 2초를 넘기면 503 `LOCK_TIMEOUT`과 `Retry-After: 1`을 돌려준다. 보유 종목은 계좌 락 안에서만 바뀌기 때문에 락 순서가 하나뿐이고 교착이 생기지 않는다.

`FOR UPDATE`를 쓰지 않은 건 외래 키 때문이다. 주문, 출금, 대사 결과처럼 계좌를 참조하는 행을 넣으면 PostgreSQL이 계좌 행에 외래 키 확인 락을 거는데, `FOR UPDATE`는 이 락과 충돌하고 `FOR NO KEY UPDATE`는 충돌하지 않는다.

수정할 수 없는 기록은 주문, 체결, 출금, 로그인 이력, 조치 이력, 대사 결과다. 스키마는 소유자 계정으로 Flyway가 만들고, 서버는 실행 중에 권한을 좁힌 앱 계정으로만 접속한다. 앱 계정 권한은 Flyway V2에서 주고, 엔티티에는 `@Immutable`을 붙였다.

### 주문 처리 순서

`POST /api/v1/orders`

1. 같은 계좌, 같은 멱등키의 결과가 있으면 시세 조회와 락 없이 저장된 행으로 처음 응답을 다시 만들어 `Idempotency-Replayed: true`와 함께 돌려준다. Redis가 멈춰도 재요청은 처리된다
2. 종목을 확인하고 시세를 트랜잭션 밖에서 읽는다. 락을 잡고 있는 시간을 줄이기 위해서다
3. 계좌 락 → 멱등키 재확인 → 계좌 상태 → 주문 가능 시간(휴장일 뺀 평일 09:00~15:20) → 시세 지연 60초 → 예수금 또는 보유 수량
4. 예수금, 보유 종목, 주문, 체결 내역을 한 커밋으로 저장한다. 체결이면 커밋 뒤 별도 스레드에서 FDS ③(새 IP 직후 거래)을 기록한다. 이 기록이 늦거나 실패해도 주문 결과는 바뀌지 않는다

### 출금 처리 순서

`POST /api/v1/accounts/me/withdrawals`

1. 계좌 락 → 멱등키 확인 → 계좌 상태
2. FDS가 락 안에서 24시간 완료 출금 합계를 읽어 대량 출금(500만 원 이상)인지 판단한다. 합계를 락 밖에서 읽으면 동시에 들어온 분할 출금이 서로를 보지 못하고 함께 통과한다
3. 출금 비밀번호가 필요하면 428(저장 안 함), 틀리면 422와 남은 횟수를 준다. 5회째에는 계좌 `TEMP_LOCKED`, 거절 출금, 조치 이력, 탐지 기록을 한 커밋으로 남기고 423을 준다
4. 예수금이 부족하면 거절로 저장하고, 충분하면 차감해 출금을 저장한다. 비밀번호를 예수금보다 먼저 확인하므로 오류 횟수는 잔액과 관계없이 쌓인다

### FDS와 원장의 경계

FDS(윤제정 담당)와 원장은 `fds.api` 인터페이스 하나로만 만나고, 트랜잭션 규칙은 그 인터페이스 메서드에 선언했다.

- FDS는 통과, 비밀번호 필요, 불일치, 잠금 같은 판단 결과만 돌려준다. 계좌 상태, 오류 횟수, 예수금은 원장만 바꾸므로 계좌를 바꾸는 코드가 한 곳이다
- 출금 판단과 기록 메서드는 `MANDATORY`다. 계좌 락을 잡은 원장 트랜잭션 밖에서 부르면 실패한다
- 새 접속 판단 메서드는 `REQUIRES_NEW`다. 메일 발송이 실패해 로그인이 503이 돼도 새 접속 시도 기록은 남는다. 메일은 DB 트랜잭션 밖에서 보낸다

### 원장 대사

평일 16:00(Asia/Seoul)에 서버 스케줄러가 모든 계좌를 점검한다.

- 기대 예수금은 초기 예수금 − 매수 체결금액 + 매도 체결금액 − 완료 출금이고, 기대 보유 수량은 매수 수량 − 매도 수량이다. 한쪽에만 있는 종목도 불일치로 본다. 일치한 계좌도 결과를 모두 남긴다
- 읽기 전용 `REPEATABLE READ` 스냅샷 하나에서 비교하고, 결과는 비교가 끝난 뒤 별도 트랜잭션에서 저장한다. 계좌 락을 잡지 않으므로 대사 중에도 주문과 출금이 멈추지 않는다
- 실행 중인 대사는 한 건만 둔다. `reconcile_run`의 부분 UNIQUE 인덱스와 `INSERT … ON CONFLICT DO NOTHING`을 쓴다. 30분 넘게 끝나지 않은 실행은 서버 기동 때와 다음 실행 전에 `FAILED`로 정리한다. 수동 실행과 겹치면 1분 간격으로 최대 10회 다시 시도한다
- 불일치나 실패는 관제 화면(`/topic/admin/reconcile`)에 알린다. GitHub Actions(`reconcile.yml`)도 내부 토큰으로 결과를 읽어 문제가 있으면 이슈를 만든다. 이슈에 계좌번호는 넣지 않는다

## 인증과 보안

| 항목 | 구현 |
|---|---|
| 새 접속 본인 인증 | 처음 보는 IP나 기기면 토큰 대신 202와 이메일 인증 코드를 준다. 코드는 5분 유효, 5회 틀리면 무효, 재발송은 60초 간격 |
| 인증 코드 저장 | `SecureRandom` 6자리. Redis에는 SHA-256 해시만 두고, 확인과 삭제를 Lua 스크립트 한 번으로 처리해 한 번만 쓰인다 |
| 로그인 실패 | 30분 안에 5회 연속 틀리면 30분 잠금(423). 없는 아이디도 같은 응답이고, 더미 해시와 비교해 처리 시간으로도 구분되지 않는다 |
| 권한 | URL 권한 표에 없는 `/api/**`는 인증 필요(기본 닫힘). 관리자는 계좌가 없고 주문, 출금 API는 403(직무 분리) |
| 비밀값 | 로그인 비밀번호와 출금 비밀번호는 BCrypt. `JWT_SECRET`이 비었거나 32바이트 미만이면 서버가 뜨지 않는다. 관리자 계정에 기본 비밀번호가 없다 |
| 접속 IP | 기본은 `X-Forwarded-For`를 읽지 않는다. 운영에서는 nginx 고정 주소 하나만 신뢰 프록시로 둔다. 사설 대역 전체를 믿으면 IP를 꾸민 요청이 새 접속 판단을 피한다 |
| WebSocket | CONNECT 때 JWT를 검증하고, 구독은 허용 목록으로만 받는다. 시세, 순위, 위험 의심은 누구나, `/topic/admin/**`은 관리자만 구독한다. 클라이언트가 보내는 메시지는 모두 거절한다 |

## 장애 시 동작

| 장애 | 동작 |
|---|---|
| 계좌 락 대기 2초 초과 | 503 `LOCK_TIMEOUT`. 아무것도 저장하지 않으므로 같은 키로 다시 보낼 수 있다 |
| Redis | 새 주문은 시세를 읽을 수 없어 503이고 저장하지 않는다. 이미 처리된 키의 재요청은 처음 결과를 준다. 출금은 Redis를 쓰지 않아 그대로 처리된다. 아는 기기의 로그인은 Access Token만 주고 새 접속 로그인은 막는다. Redis 호출 시간 제한은 200ms |
| 메일 서버 | 회원가입과 새 접속 로그인만 503. 아는 기기의 로그인은 그대로 된다 |
| 출금 중 DB 장애로 누적액이나 비밀번호를 확인할 수 없음 | 금액과 관계없이 출금하지 않고 503 |

## 테스트로 증명한 것

원장 테스트는 Testcontainers로 띄운 PostgreSQL과 Redis에서 실제 여러 스레드로 돌린다. 테스트 메서드에는 `@Transactional`을 붙이지 않는다. 붙이면 커밋이 일어나지 않아 락과 제약을 증명할 수 없다.

`test` 304개와 `fdsScenarioTest` 10개가 모두 통과했다(develop `df99f90`, 2026-11-20, 로컬 PC). PR마다 GitHub Actions에서도 같은 테스트가 돈다.

| 테스트 | 증명하는 것 |
|---|---|
| `order.OrderConcurrencyTest` | 같은 멱등키 주문 10건 동시 → 1건만 체결, 10건 응답 같음 / 예수금 1,000만 원에 300만 원 매수 10건 동시 → 3건만 체결, 예수금 음수 없음 / 락 대기 2초 초과 → 503, 아무것도 저장 안 됨 |
| `withdraw.WithdrawalConcurrencyTest` | 300만 원 2건 동시 → 1건 201, 1건 428, 예수금은 300만 원만 줄어듦 / 같은 키 출금 10건 동시 → 1건만 출금 / 같은 계좌 매수, 매도, 출금 100건 동시 → 예수금과 보유 수량이 내역 합계와 같음 |
| `order.OrderRollbackTest`, `withdraw.WithdrawalRollbackTest` | 처리 중 강제 예외 → 원장, 주문(출금), 내역, 탐지 기록이 모두 처리 전 그대로이고, 같은 키로 다시 보내면 처리됨 |
| `member.SignupRollbackTest` | 첫 기기 등록에서 실패하면 회원과 계좌가 남지 않음 |
| `reconcile.ReconcileConsistencyTest` | 대사 결과를 저장하는 중에도 그 계좌의 주문과 출금이 락 대기 없이 처리됨 / 대사 중 거래가 들어와도 거짓 불일치 없음 |
| `common.db.AppDbPermissionTest` | 앱 계정으로 감사 테이블 수정, 삭제 거부 / 예수금 음수, 가격 없는 체결, 정의되지 않은 코드값을 DB 제약이 거부 |
| `order.OrderRedisOutageTest` | Redis가 멈추면 새 주문 503, 저장 없음 / 이미 처리된 키 재요청은 처음 결과 / 출금은 그대로 / 시간 제한 200ms가 실제 연결에 걸림 |
| `scenario.LedgerAttackScenarioTest` | 실제 FDS로 도는 공격 시나리오 4종: 인증 코드 5회 오류, 동시 분할 출금, 출금 비밀번호 5회 오류, 같은 멱등키 동시 주문. 비밀번호 5회 오류는 계좌, 거절 출금, 조치 이력, 탐지 기록의 `xmin`이 같은지로 한 커밋임을 확인 |

### 보호 장치를 빼면 실패하는지

보호 장치를 빼거나 바꾼 코드로 테스트를 한 번 돌리고, 코드는 되돌린 뒤 결과를 PR 본문에 남겼다.

| 빼거나 바꾼 것 | 결과 | PR |
|---|---|---|
| 계좌 락 제거 | 동시성 테스트 8개 중 7개 실패. 300만 원 2건 동시 출금이 둘 다 나가고, 100건 동시 요청 뒤 예수금이 내역 합계와 78만 원 어긋남 | #17 |
| 락 안의 멱등키 재확인 제거 | 같은 키 동시 요청 중 1건만 처리되고 나머지는 UNIQUE 위반(500) | #17, #19 |
| `FOR NO KEY UPDATE` → `FOR UPDATE` | 대사 결과 저장 중 주문과 출금이 락을 기다림 | #18 |
| 관리자 잠금, 해제의 계좌 락 제거 | 진행 중인 출금을 기다리지 않고 해제 | #18 |
| 이미 확인한 탐지의 재확인 거부 제거 | 동시 확인 5건에 조치 이력 5건 | #18 |
| Redis 시간 제한 200ms 제거 | 기본값 60초까지 요청이 묶임 | #18 |
| `forward-headers-strategy=native` 켬 | 꾸민 IP를 새 접속으로 판단(202) | #18 |
| STOMP 구독을 허용 목록 대신 `/topic/admin/` 접두사만 차단 | `/topic/**` 구독으로 관리자 경보 수신 | #18 |
| 5회째 거절 출금을 별도 트랜잭션에 저장 | 계좌와 거절 출금의 `xmin`이 달라짐(한 커밋이 아님) | #19 |
| 인증 코드 5회째 오류의 실패 기록 제거 | 탐지 기록이 `FAIL`이 아니라 `PENDING`으로 남음 | #19 |

### 응답 시간

k6로 동시 사용자 50명이 매수 → 매도 → 출금을 반복하게 했다(요청 사이 1초). 주문 p95 93.7ms, 출금 p95 47.6ms, 요청 실패 0건이었고 측정 뒤 원장 불일치도 0건이었다(2026-11-17, 로컬 PC). 방법은 [`load-test/README.md`](load-test/README.md)에 있다.

## 범위

- 주문은 시장가만 받는다. 지정가와 미체결 주문을 넣으려면 주문할 때 예수금과 보유 수량을 묶어 두고, 대사 기준식에 묶인 금액을 반영하고, 동시성 테스트를 다시 짜야 한다
- 출금은 모의 출금이라 실제 이체나 대외 기관 연동은 없다

## 준비

1. JDK 17과 Docker Desktop을 설치한다.
2. 저장소 루트에서 `infra/.env.example`을 복사해 `infra/.env`를 만들고 값을 채운다. 서버는 이 파일을 직접 읽는다. `infra/.env`는 커밋하지 않는다.

| 키 | 설명 |
|---|---|
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | DB 소유자 계정. Flyway가 테이블을 만들고 앱 계정에 권한을 줄 때만 쓴다 |
| `DB_APP_USER`, `DB_APP_PASSWORD` | 서버가 실행 중에 쓰는 앱 DB 계정(기본 `stockshield_app`). 비밀번호를 비워 두면 서버가 뜨지 않는다 |
| `MAIL_*` | 인증 코드 메일을 보낼 Gmail 계정과 앱 비밀번호 |
| `JWT_SECRET` | 토큰 서명 키. 32바이트 이상이어야 서버가 뜬다 |
| `INTERNAL_TOKEN` | 정기 대사 결과 확인 API용 토큰. 비워 두면 그 API는 항상 401 |
| `CORS_ALLOWED_ORIGINS` | 화면을 다른 주소에서 띄울 때만 필요하다. 로컬 개발 화면은 Vite 프록시를 거쳐 같은 주소처럼 부른다 |
| `DEMO_MODE` | `true`면 주문 가능 시간이 아니어도 주문을 받는다 |
| `ADMIN_*` | 최초 관리자 계정. 아래 참고 |
| `FINLIGHT_API_KEY`, `KIS_APP_KEY`, `KIS_APP_SECRET` | 뉴스 수집(finlight)과 호가 조회(한국투자증권 REST)용 |

`JWT_SECRET`은 PowerShell에서 이렇게 만든다.

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

### 최초 관리자 계정

관리자는 회원가입으로 만들 수 없다. Flyway V4가 `.env`의 `ADMIN_LGN_ID`, `ADMIN_EML_ADDR`, `ADMIN_PWD_HASH`로 한 번 넣는다. 로컬에서는 비워 둬도 되지만, 운영(prod 프로필)에서 비어 있으면 서버가 뜨지 않는다.

`ADMIN_PWD_HASH`에는 비밀번호가 아니라 BCrypt 해시를 넣는다.

```powershell
cd backend
.\gradlew.bat adminPasswordHash --console=plain -q
```

해시에 `$`가 들어 있어서 넣는 방법이 다르다.

- 로컬 `infra/.env`: 따옴표 없이 `ADMIN_PWD_HASH=$2a$10$...`
- 배포 서버 `.env`(Docker Compose가 읽음): 작은따옴표로 `ADMIN_PWD_HASH='$2a$10$...'`

로컬 DB에 V4가 이미 빈 값으로 적용됐다면 다시 돌지 않는다. 이때는 볼륨을 지우고 다시 띄운다. 로컬 데이터가 모두 지워진다.

```powershell
docker compose -f infra/docker-compose.yml down -v
docker compose -f infra/docker-compose.yml up -d
```

## 실행

저장소 루트에서 인프라를 띄운 뒤 서버를 실행한다. 서버는 `http://localhost:8080`에서 뜨고, 처음 실행할 때 Flyway가 테이블을 만든다.

```powershell
docker compose -f infra/docker-compose.yml up -d
cd backend
.\gradlew.bat bootRun
```

시세는 시세 수집기(`collector/`)가, 뉴스 요약과 탐지 사유 설명은 AI 서버(`ai-server/`)가 떠 있어야 동작한다. 실행 방법은 각 폴더의 README를 따른다.

## 테스트

Docker Desktop을 켜고 실행한다. 테스트가 PostgreSQL과 Redis 컨테이너를 직접 띄운다.

```powershell
cd backend
.\gradlew.bat test
```

결과는 `build/reports/tests/test/index.html`에서 본다.

FDS 공격 시나리오(`@Tag("fds-scenario")`)는 `test`에서 빠지고 따로 돌린다. 가짜 FDS 없이 실제 FDS로 돈다.

```powershell
.\gradlew.bat fdsScenarioTest
```

GitHub Actions에서는 PR마다 `ci.yml`이 `test`를, `fds-scenario.yml`이 `fdsScenarioTest`를 돌린다. 둘 다 통과해야 merge된다.

## 패키지 구조

| 패키지 | 내용 | 담당 |
|---|---|---|
| `common` | Clock, 거래추적번호, 공통 에러 응답, 요청 정보(IP, 기기, User-Agent) 정규화, 스케줄러 설정 | 조성우 |
| `auth` | 로그인, 새 접속 이메일 인증 코드, JWT, Spring Security, STOMP 접속 인증 | 조성우 |
| `member` | 회원가입(회원, 계좌, 첫 신뢰 기기를 한 트랜잭션으로) | 조성우 |
| `ledger` | 계좌, 계좌 락, 보유 종목, 체결 내역, 계좌번호(Luhn)와 주문번호 채번 | 조성우 |
| `order` | 시장가 매수, 매도, 주문 가능 시간 | 조성우 |
| `withdraw` | 모의 출금, 출금 내역 | 조성우 |
| `account` | 내 계좌 요약, 보유 종목, 주문 내역 조회 | 조성우 |
| `reconcile` | 원장 대사(정기, 수동), 내부 결과 확인 API | 조성우 |
| `admin` | 관제 API: 탐지 목록, 상세, 확인, 계좌 잠금과 해제 | 조성우 |
| `fds.api` | 원장과 FDS를 잇는 인터페이스와 트랜잭션 규칙 | 조성우, 윤제정 |
| `fds` | FDS 판단(새 접속, 대량 출금, 새 IP 직후 거래, 위험 의심 종목), 탐지 기록, AI 사유 설명 요청 | 윤제정 |
| `market`, `ws` | 시세 소비(Kafka → Redis), 분봉과 일봉, 호가, 시세 API, WebSocket 설정 | 윤제정 |
| `news` | 뉴스 수집(finlight)과 조회 | 윤제정 |

DB 스키마는 `src/main/resources/db/migration`의 Flyway SQL(V1~V6)로만 바꾼다. Hibernate는 `ddl-auto=validate`로 엔티티와 스키마가 맞는지만 확인한다. develop에 들어간 마이그레이션 파일은 고치지 않고 새 번호로 추가한다.
