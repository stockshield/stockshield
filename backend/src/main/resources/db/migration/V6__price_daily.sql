-- 일봉 시세 (문서 수정 대기 목록 #90)
-- 기준: MKT_VIEW_002(차트), 테이블 정의서 '테이블정의서' price_minute 행(컬럼 타입·제약을 같게 맞춤)
-- develop에 들어간 뒤에는 이 파일을 고치지 않고 다음 번호로 보탠다(대기 목록 #41).
--
-- 장 마감 후 공식 일봉으로 그날 행을 덮어쓰려고 UPDATE 권한 줌
-- 삭제가 필요한 기능이 없어 DELETE는 주지 않음(대기 목록 #3)

CREATE TABLE price_daily (
    tck_cd     VARCHAR(10)    NOT NULL,
    bs_dt      VARCHAR(8)     NOT NULL,
    open_prc   NUMERIC(15, 0) NOT NULL,
    high_prc   NUMERIC(15, 0) NOT NULL,
    low_prc    NUMERIC(15, 0) NOT NULL,
    close_prc  NUMERIC(15, 0) NOT NULL,
    trd_vol    BIGINT         NOT NULL,
    reg_ts     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_price_daily PRIMARY KEY (tck_cd, bs_dt),
    CONSTRAINT fk_price_daily_tck_cd FOREIGN KEY (tck_cd) REFERENCES stock_master (tck_cd),
    CONSTRAINT ck_price_daily_trd_vol CHECK (trd_vol >= 0)
);

GRANT SELECT, INSERT, UPDATE ON price_daily TO "${appDbUser}";
