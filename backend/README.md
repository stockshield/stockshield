# StockShield 메인 서버 (backend)

Spring Boot 3.3.5 · Java 17 · PostgreSQL 15.19 · Flyway

## 준비

1. JDK 17, Docker Desktop
2. `infra/.env` (윤제정 님의 `infra/.env.example`을 복사) 에 앱 DB 계정 비밀번호를 한 줄 추가한다.

   ```
   DB_APP_PASSWORD=원하는-비밀번호
   ```

   - 마이그레이션은 `POSTGRES_USER`(소유자 계정)로 돌고, 앱은 `stockshield_app` 계정으로 접속한다.
   - 앱 계정은 감사 테이블(orders, trade_history, withdrawal 등)을 수정·삭제할 수 없다.

## 테스트

Docker Desktop이 켜져 있어야 한다. 테스트가 PostgreSQL 15.19 컨테이너를 직접 띄운다.

```powershell
cd backend
.\gradlew.bat test
```

결과 보고서: `backend/build/reports/tests/test/index.html`

## 실행

```powershell
docker compose -f infra/docker-compose.yml up -d
cd backend
.\gradlew.bat bootRun
```

## 구조

| 위치 | 내용 |
|---|---|
| `src/main/resources/db/migration` | Flyway SQL. 테이블·제약·인덱스·권한은 여기서만 바꾼다 (Hibernate는 `validate`만) |
| `com.stockshield.common` | Clock, JPA Auditing, 거래추적번호, 공통 에러 응답 |
| `com.stockshield.fds` | FDS (윤제정 담당) |

문서에 없는 결정과 문서와 다르게 승인한 동작은 저장소에 두지 않고 팀 문서 수정 목록으로 관리한다. 문서는 그 목록으로 한꺼번에 고친다.
