-- 부하 테스트 회원 50명(loadtest01~loadtest50)과 계좌·신뢰 접속을 넣음. 로컬 DB 전용, 운영 DB에 돌리지 않음
-- 가입 API는 이메일 인증 코드가 필요해 회원 50명을 만들 수 없어 소유자 계정으로 DB에 직접 넣음
-- 계좌는 가입과 같은 규칙('100' + acnt_no_seq 8자리 + Luhn, PKG_10M, 예수금 = 초기 금액)이라 원장 대사가 일치로 나옴
-- 신뢰 접속은 SIGNUP으로 넣어 로그인이 200(KNOWN)이 되고 주문에 TRADE_NEW_IP가 생기지 않게 함 (대기 목록 #53)
-- 여러 번 돌려도 이미 있는 회원·계좌·신뢰 접속은 건너뜀
--
-- 실행(저장소 루트):
--   docker cp backend\load-test\seed.sql stockshield-postgres:/tmp/seed.sql
--   docker exec stockshield-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -f /tmp/seed.sql'

BEGIN;

-- BCrypt 해시($2a$10$)를 DB에서 만들려고 pgcrypto를 임시 스키마에 올렸다가 같은 트랜잭션에서 지움
CREATE SCHEMA loadtest_tmp;
CREATE EXTENSION pgcrypto SCHEMA loadtest_tmp;

DO $$
DECLARE
    v_pwd_hash  text := loadtest_tmp.crypt('Loadtest!2026', loadtest_tmp.gen_salt('bf', 10));
    v_wdr_hash  text := loadtest_tmp.crypt('123456', loadtest_tmp.gen_salt('bf', 10));
    v_lgn_id    text;
    v_dvc_id    text;
    v_mbr_no    bigint;
    v_body      text;
    v_sum       int;
    v_d         int;
    v_dbl       boolean;
    i           int;
    j           int;
BEGIN
    FOR i IN 1..50 LOOP
        v_lgn_id := 'loadtest' || lpad(i::text, 2, '0');
        -- k6 스크립트의 기기 ID와 같아야 함
        v_dvc_id := '5f0c0000-0000-4000-8000-0000000000' || lpad(i::text, 2, '0');

        INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr)
        VALUES (v_lgn_id, v_pwd_hash, v_lgn_id, v_lgn_id || '@loadtest.invalid')
        ON CONFLICT (lgn_id) DO NOTHING;

        SELECT mbr_no INTO v_mbr_no FROM member WHERE lgn_id = v_lgn_id;

        IF NOT EXISTS (SELECT 1 FROM account WHERE mbr_no = v_mbr_no) THEN
            v_body := '100' || lpad(nextval('acnt_no_seq')::text, 8, '0');
            -- AccountNumbers.luhnCheckDigit와 같은 계산
            v_sum := 0;
            v_dbl := true;
            FOR j IN REVERSE length(v_body)..1 LOOP
                v_d := substr(v_body, j, 1)::int;
                IF v_dbl THEN
                    v_d := v_d * 2;
                    IF v_d > 9 THEN
                        v_d := v_d - 9;
                    END IF;
                END IF;
                v_sum := v_sum + v_d;
                v_dbl := NOT v_dbl;
            END LOOP;

            INSERT INTO account (acnt_no, mbr_no, init_pkg_cd, init_dps_amt, dps_bal, wdr_pwd)
            VALUES (v_body || ((10 - v_sum % 10) % 10)::text, v_mbr_no, 'PKG_10M', 10000000, 10000000, v_wdr_hash);
        END IF;

        -- 127.0.0.1로 붙든 ::1로 붙든 아는 IP가 되게 둘 다 넣음
        INSERT INTO trusted_access (mbr_no, conn_ip, dvc_id, user_agt, reg_dv_cd)
        VALUES (v_mbr_no, '127.0.0.1', v_dvc_id, 'k6-load-test', 'SIGNUP'),
               (v_mbr_no, '0:0:0:0:0:0:0:1', v_dvc_id, 'k6-load-test', 'SIGNUP')
        ON CONFLICT (mbr_no, conn_ip, dvc_id) DO NOTHING;
    END LOOP;
END
$$;

DROP SCHEMA loadtest_tmp CASCADE;

COMMIT;

SELECT count(*) AS load_members,
       count(a.acnt_no) AS load_accounts,
       sum(a.dps_bal) AS total_dps_bal
FROM member m
LEFT JOIN account a ON a.mbr_no = m.mbr_no
WHERE m.lgn_id LIKE 'loadtest__';
