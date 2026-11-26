-- 앱(실행) DB 계정과 권한
-- 기준: 요구사항명세서 SYS_SEC_001 #3·구현방안, TRD_LED_001 #5, 테이블 정의서 공통 규칙 'INSERT 전용'·'부분 갱신'·'스키마 관리'
--
-- 마이그레이션은 소유자 계정(POSTGRES_USER)으로 돌고, 애플리케이션은 아래 앱 계정으로만 접속한다.
-- 앱 계정은 테이블 소유자가 아니므로 DDL을 실행할 수 없고, 아래에 적은 권한만 가진다.
-- 계정 이름·비밀번호는 Flyway placeholder(appDbUser, appDbPassword)로 받아 저장소에 남기지 않는다.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbUser}') THEN
        CREATE ROLE "${appDbUser}" LOGIN PASSWORD '${appDbPassword}';
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), '${appDbUser}');
END
$$;

GRANT USAGE ON SCHEMA public TO "${appDbUser}";

-- 채번 시퀀스와 BIGSERIAL 시퀀스: 다음 값 받기만
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${appDbUser}";

-- 변경 가능한 원장·기준 테이블: 조회·추가·수정. 삭제가 필요한 기능이 없으므로 DELETE는 주지 않는다.
GRANT SELECT, INSERT, UPDATE ON
    member, account, trusted_access, holding, stock_master,
    price_minute, news, news_stock, reconcile_run
TO "${appDbUser}";

-- INSERT 전용(감사 기록): 수정·삭제 권한 없음
GRANT SELECT, INSERT ON
    orders, trade_history, withdrawal, login_history, fds_action_log, reconcile_result
TO "${appDbUser}";

-- fds_detection: 삭제 불가, 정해진 컬럼만 갱신 (컬럼 단위 GRANT)
GRANT SELECT, INSERT ON fds_detection TO "${appDbUser}";
GRANT UPDATE (rslt_cd, wdr_no, ai_rsn_cont, ai_chk_cont, ai_stat_cd, chk_yn, mod_ts) ON fds_detection TO "${appDbUser}";
