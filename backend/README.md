# StockShield 메인 서버 (backend)

Spring Boot 3.3.5, Java 17, PostgreSQL 15.19, Redis 7.2.17, Flyway

## 준비

1. JDK 17, Docker Desktop을 설치한다.
2. `infra/.env.example`을 복사해 `infra/.env`를 만들고 값을 채운다. `infra/.env`는 커밋하지 않는다.

| 키 | 설명 |
|---|---|
| `DB_APP_PASSWORD` | 앱 DB 계정 비밀번호. 비워 두면 서버가 안 뜬다 |
| `MAIL_*` | 인증 코드 메일용 Gmail 계정. 앱 비밀번호는 조성우에게 따로 받는다 |
| `JWT_SECRET` | 토큰 서명 키. 32바이트 이상이어야 서버가 뜬다 |
| `INTERNAL_TOKEN` | 정기 대사 결과 확인 API용 토큰. 비워 두면 그 API는 항상 401 |
| `CORS_ALLOWED_ORIGINS` | 프론트 주소. 기본값 `http://localhost:5173` |
| `DEMO_MODE` | `true`면 장 운영 시간이 아니어도 주문을 받는다 |
| `ADMIN_*` | 최초 관리자 계정. 아래 참고 |

`JWT_SECRET`은 PowerShell에서 이렇게 만들면 된다.

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

## 최초 관리자 계정

관리자는 회원가입으로 만들 수 없고, Flyway V4가 `.env`의 `ADMIN_LGN_ID`, `ADMIN_EML_ADDR`, `ADMIN_PWD_HASH`로 한 번 넣는다.
로컬에서는 비워 둬도 되지만, 운영(prod 프로필)에서 비어 있으면 서버가 안 뜬다.

`ADMIN_PWD_HASH`에는 비밀번호가 아니라 BCrypt 해시를 넣는다. 해시는 아래 명령으로 만든다.

```powershell
cd backend
.\gradlew.bat adminPasswordHash --console=plain -q
```

해시에 `$`가 들어 있어서 넣는 방법이 다르다.

- 로컬 `infra/.env`: 따옴표 없이 `ADMIN_PWD_HASH=$2a$10$...`
- 배포 서버 `.env`(Docker Compose가 읽음): 작은따옴표로 `ADMIN_PWD_HASH='$2a$10$...'`

로컬 DB에 V4가 이미 빈 값으로 적용됐다면 다시 돌지 않는다. 이때는 볼륨을 지우고 다시 띄운다(로컬 데이터가 다 지워진다).

```powershell
docker compose -f infra/docker-compose.yml down -v
docker compose -f infra/docker-compose.yml up -d
```

## 실행

```powershell
docker compose -f infra/docker-compose.yml up -d
cd backend
.\gradlew.bat bootRun
```

메일 계정 없이 화면만 볼 때는 `fake-fds` 프로필로 띄운다. 로그인할 때 이메일 인증을 건너뛴다. 곧 지울 예정이다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=fake-fds"
```

## 테스트

Docker Desktop을 켜고 실행한다. PostgreSQL, Redis 컨테이너는 테스트가 알아서 띄운다.

```powershell
cd backend
.\gradlew.bat test
```

결과는 `build/reports/tests/test/index.html`에서 본다. FDS 공격 시나리오는 `.\gradlew.bat fdsScenarioTest`로 따로 돌린다.

## 구조

| 위치 | 내용 |
|---|---|
| `src/main/resources/db/migration` | Flyway SQL. 스키마는 여기서만 바꾸고, 이미 develop에 들어간 파일은 고치지 않고 새 번호로 추가한다 |
| `com.stockshield.common` | Clock, 거래추적번호, 공통 에러 응답 등 |
| `com.stockshield.auth` | 로그인, 토큰, Spring Security |
| `com.stockshield.member` | 회원가입 |
| `com.stockshield.ledger` | 계좌, 원장 |
| `com.stockshield.fds` | FDS. 다른 패키지는 `fds.api`의 `FdsService`로만 부른다 |
| `com.stockshield.market`, `com.stockshield.ws` | 시세, WebSocket |
