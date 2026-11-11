# 설계 결정 기록

문서(기획서·요구사항명세서·테이블 정의서·API 명세서)에 없는 내용을 정했거나, 문서와 다르게 하기로 승인한 것을 남긴다.
문서는 나중에 이 기록으로 한꺼번에 고친다. 새 항목은 위에 추가한다.

| # | 날짜 | 내용 | 이유 | 관련 문서 위치 |
|---|---|---|---|---|
| 5 | 2026-11-12 | orders의 CHECK를 `ord_stat_cd <> 'FILLED' OR (ord_prc IS NOT NULL AND ord_prc > 0)`로 둔다 (조성우 승인) | 문서의 `ord_stat_cd <> 'FILLED' OR ord_prc > 0`은 ord_prc가 NULL이면 결과가 NULL이 되어 CHECK를 통과한다. PostgreSQL에서 가격 없는 체결 주문이 저장되는 것을 확인했다 | 테이블 정의서 orders.ord_prc |
| 4 | 2026-11-12 | 에러 코드 `RESOURCE_NOT_FOUND`(404)를 추가한다. Spring MVC 기본 오류(본문 형식 오류, 필수 헤더 누락, 지원하지 않는 메서드 등)는 HTTP 상태를 유지하고 code는 404면 `RESOURCE_NOT_FOUND`, 그 밖의 4xx는 `INVALID_REQUEST`로 응답한다 | 에러 코드 표에는 `STOCK_NOT_FOUND 등 *_NOT_FOUND`만 있고 없는 경로·기본 오류의 code가 정해져 있지 않다. 화면은 code로 분기하므로 모든 응답에 code가 있어야 한다 | API 명세서 '공통 규칙' 2·3 |
| 3 | 2026-11-12 | 앱 계정에는 어떤 테이블에도 DELETE 권한을 주지 않는다 | 문서는 감사 테이블의 UPDATE·DELETE 금지만 정했지만, 삭제가 필요한 기능이 없다. 최소 권한 원칙 | SYS_SEC_001 #3, 테이블 정의서 '공통 규칙' INSERT 전용 |
| 2 | 2026-11-12 | DB 계정: 소유자 = `POSTGRES_USER`(infra/.env), 앱 = `stockshield_app`(`DB_APP_USER`), 앱 비밀번호 = `DB_APP_PASSWORD`. 앱 계정 생성과 권한 부여는 Flyway `V2__app_account_grants.sql`에서 하고, 이름·비밀번호는 placeholder로 받는다 | 계정 분리는 문서에 있으나 계정 이름·생성 방법은 없다. 권한까지 Flyway로 버전 관리하고, 비밀번호는 저장소에 남기지 않는다 | SYS_SEC_001 구현방안, SYS_ENV_001 #10, 테이블 정의서 '공통 규칙' 스키마 관리 |
| 1 | 2026-11-12 | Gradle Wrapper를 8.10.2로 새로 만든다 | 문서에 Gradle 버전이 없다. 기존 Wrapper는 9.8.2였는데, Spring Boot 3.3.5는 Gradle 8 계열 기준이다 | SYS_ENV_001 |
