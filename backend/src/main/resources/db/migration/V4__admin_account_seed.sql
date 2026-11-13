-- 최초 관리자 계정 (USR_AUTH_003 #3)
-- 기준: 문서 수정 대기 목록 #17·#19·#21, 테이블 정의서 '테이블정의서' 4~11행(member)
--
-- 값은 저장소에 두지 않고 .env의 ADMIN_LGN_ID·ADMIN_EML_ADDR·ADMIN_PWD_HASH를 Flyway placeholder로 받는다.
--  ① 셋 다 비면: adminRequired가 true(운영, application-prod.yml)면 실패시켜 서버가 뜨지 않게 하고,
--     false(로컬·테스트·CI)면 넣지 않고 넘어간다. 운영에서 조용히 건너뛰면 V4가 관리자 없이 '적용됨'으로 남아 다시 돌지 않는다.
--  ② 일부만 있으면 어디서든 실패
--  ③ 아이디는 영문 소문자로 시작하는 소문자·숫자 4~16자, 이메일은 100자 이하·'@' 포함,
--     비밀번호는 BCrypt 해시($2a$10$ + 53자, 모두 60자)가 아니면 실패. 평문이나 잘린 해시로 관리자 로그인이 안 되는 일을 막는다
--  ④ 이름 '관리자', ROLE_ADMIN. 관리자는 계좌·신뢰 접속을 만들지 않는다(직무 분리 USR_AUTH_003 #4, 첫 로그인은 이메일 인증)
-- 값은 dollar quote($val$)로 감싸 작은따옴표가 들어 있어도 SQL이 깨지지 않게 한다. 여는 $val$ 뒤에 공백을 둔다
-- (닫는 기호와 placeholder 앞의 달러 기호가 붙으면 Flyway가 placeholder를 바꾸지 못한다). 앞뒤 공백은 btrim으로 지운다. PostgreSQL은 DDL·DML이 트랜잭션 안이라
-- 실패하면 아무것도 남지 않고 이 마이그레이션은 미적용으로 남는다(값을 넣고 다시 띄우면 적용된다).

DO $v4$
DECLARE
    v_lgn_id    TEXT := btrim($val$ ${adminLgnId}$val$);
    v_eml_addr  TEXT := btrim($val$ ${adminEmlAddr}$val$);
    v_pwd_hash  TEXT := btrim($val$ ${adminPwdHash}$val$);
    v_required  TEXT := lower(btrim($val$ ${adminRequired}$val$));
    v_filled    INTEGER;
BEGIN
    v_filled := (v_lgn_id <> '')::INTEGER + (v_eml_addr <> '')::INTEGER + (v_pwd_hash <> '')::INTEGER;

    IF v_filled = 0 THEN
        IF v_required = 'true' THEN
            RAISE EXCEPTION 'V4: 운영(prod)에서는 ADMIN_LGN_ID·ADMIN_EML_ADDR·ADMIN_PWD_HASH를 모두 넣어야 합니다';
        END IF;
        RAISE NOTICE 'V4: 관리자 계정 값이 없어 넣지 않고 넘어갑니다(로컬·테스트)';
        RETURN;
    END IF;

    IF v_filled < 3 THEN
        RAISE EXCEPTION 'V4: ADMIN_LGN_ID·ADMIN_EML_ADDR·ADMIN_PWD_HASH 중 일부만 있습니다. 셋 다 넣거나 모두 비워야 합니다';
    END IF;
    IF v_lgn_id !~ '^[a-z][a-z0-9]{3,15}$' THEN
        RAISE EXCEPTION 'V4: ADMIN_LGN_ID는 영문 소문자로 시작하는 소문자·숫자 4~16자여야 합니다';
    END IF;
    IF length(v_eml_addr) > 100 OR position('@' IN v_eml_addr) = 0 THEN
        RAISE EXCEPTION 'V4: ADMIN_EML_ADDR는 100자 이하이고 @를 포함해야 합니다';
    END IF;
    IF v_pwd_hash !~ '^\$2a\$10\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'V4: ADMIN_PWD_HASH는 BCrypt 해시($2a$10$로 시작하는 60자)여야 합니다. 평문 비밀번호를 넣지 마세요';
    END IF;

    INSERT INTO member (lgn_id, lgn_pwd, mbr_nm, eml_addr, role_cd)
    VALUES (v_lgn_id, v_pwd_hash, '관리자', v_eml_addr, 'ROLE_ADMIN');
END
$v4$;
