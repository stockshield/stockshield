-- StockShield 초기 스키마
-- 기준: 테이블 정의서 1.6 (테이블정의서·공통 규칙·코드 정의 시트)
-- 제약 이름은 테이블 정의서의 이름(UK_MEMBER_01 등)을 소문자로 쓰고,
-- 정의서에 이름이 없는 PK·FK·CHECK는 pk_/fk_/ck_ + 테이블 + 컬럼으로 짓는다.
-- 코드 컬럼 CHECK는 '코드 정의' 시트의 값만 허용한다 (테이블 정의서 공통 규칙 '코드 CHECK').

-- ---------------------------------------------------------------------------
-- 채번용 시퀀스 (테이블 정의서 공통 규칙 '채번')
--   계좌 '100' + 8자리(acnt_no_seq) + Luhn 검증번호 1자리
--   주문 'O' + YYYYMMDD + 9자리(ord_no_seq), 출금 'W' + YYYYMMDD + 9자리(wdr_no_seq)
-- ---------------------------------------------------------------------------
CREATE SEQUENCE acnt_no_seq START WITH 1 MINVALUE 1 MAXVALUE 99999999 NO CYCLE;
CREATE SEQUENCE ord_no_seq START WITH 1 MINVALUE 1 MAXVALUE 999999999 NO CYCLE;
CREATE SEQUENCE wdr_no_seq START WITH 1 MINVALUE 1 MAXVALUE 999999999 NO CYCLE;

-- ---------------------------------------------------------------------------
-- member: 회원 (사용자 및 관리자)
-- ---------------------------------------------------------------------------
CREATE TABLE member (
    mbr_no    BIGSERIAL    NOT NULL,
    lgn_id    VARCHAR(50)  NOT NULL,
    lgn_pwd   VARCHAR(255) NOT NULL,
    mbr_nm    VARCHAR(50)  NOT NULL,
    eml_addr  VARCHAR(100) NOT NULL,
    role_cd   VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    reg_ts    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_ts    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_member PRIMARY KEY (mbr_no),
    CONSTRAINT uk_member_01 UNIQUE (lgn_id),
    CONSTRAINT ck_member_role_cd CHECK (role_cd IN ('ROLE_USER', 'ROLE_ADMIN'))
);

-- ---------------------------------------------------------------------------
-- account: 계좌 (모의투자 원장 마스터) - 비관적 락 대상 (SELECT ... FOR NO KEY UPDATE)
-- ---------------------------------------------------------------------------
CREATE TABLE account (
    acnt_no           VARCHAR(20)    NOT NULL,
    mbr_no            BIGINT         NOT NULL,
    init_pkg_cd       VARCHAR(10)    NOT NULL,
    init_dps_amt      NUMERIC(19, 0) NOT NULL,
    dps_bal           NUMERIC(19, 0) NOT NULL,
    wdr_pwd           VARCHAR(255)   NOT NULL,
    wdr_pwd_fail_cnt  INTEGER        NOT NULL DEFAULT 0,
    acnt_stat_cd      VARCHAR(12)    NOT NULL DEFAULT 'ACTIVE',
    reg_ts            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_ts            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_account PRIMARY KEY (acnt_no),
    CONSTRAINT fk_account_mbr_no FOREIGN KEY (mbr_no) REFERENCES member (mbr_no) ON DELETE RESTRICT,
    CONSTRAINT uk_account_01 UNIQUE (mbr_no),
    CONSTRAINT ck_account_init_dps_amt CHECK (init_dps_amt > 0),
    CONSTRAINT ck_account_dps_bal CHECK (dps_bal >= 0),
    CONSTRAINT ck_account_wdr_pwd_fail_cnt CHECK (wdr_pwd_fail_cnt BETWEEN 0 AND 5),
    CONSTRAINT ck_account_init_pkg_cd CHECK (init_pkg_cd IN ('PKG_10M', 'PKG_30M', 'PKG_50M')),
    CONSTRAINT ck_account_acnt_stat_cd CHECK (acnt_stat_cd IN ('ACTIVE', 'TEMP_LOCKED', 'LOCKED'))
);

-- ---------------------------------------------------------------------------
-- login_history: 로그인 이력 - INSERT 전용
-- ---------------------------------------------------------------------------
CREATE TABLE login_history (
    lgn_hist_seq  BIGSERIAL    NOT NULL,
    lgn_id        VARCHAR(50)  NOT NULL,
    conn_ip       VARCHAR(45)  NOT NULL,
    dvc_id        VARCHAR(64)  NOT NULL,
    user_agt      VARCHAR(255) NOT NULL,
    succ_yn       CHAR(1)      NOT NULL,
    add_auth_yn   CHAR(1)      NOT NULL DEFAULT 'N',
    reg_ts        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_login_history PRIMARY KEY (lgn_hist_seq),
    CONSTRAINT ck_login_history_succ_yn CHECK (succ_yn IN ('Y', 'N')),
    CONSTRAINT ck_login_history_add_auth_yn CHECK (add_auth_yn IN ('Y', 'N'))
);
CREATE INDEX idx_login_hist_01 ON login_history (lgn_id, reg_ts);

-- ---------------------------------------------------------------------------
-- trusted_access: 신뢰 접속 정보 (가입·새 접속 인증을 통과한 IP·기기)
-- ---------------------------------------------------------------------------
CREATE TABLE trusted_access (
    trst_seq     BIGSERIAL    NOT NULL,
    mbr_no       BIGINT       NOT NULL,
    conn_ip      VARCHAR(45)  NOT NULL,
    dvc_id       VARCHAR(64)  NOT NULL,
    user_agt     VARCHAR(255) NOT NULL,
    reg_dv_cd    VARCHAR(10)  NOT NULL,
    reg_ts       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_use_ts  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_trusted_access PRIMARY KEY (trst_seq),
    CONSTRAINT fk_trusted_access_mbr_no FOREIGN KEY (mbr_no) REFERENCES member (mbr_no) ON DELETE CASCADE,
    CONSTRAINT uk_trst_acc_01 UNIQUE (mbr_no, conn_ip, dvc_id),
    CONSTRAINT ck_trusted_access_reg_dv_cd CHECK (reg_dv_cd IN ('SIGNUP', 'EMAIL_AUTH'))
);
CREATE INDEX idx_trst_acc_01 ON trusted_access (mbr_no, conn_ip);
CREATE INDEX idx_trst_acc_02 ON trusted_access (mbr_no, dvc_id);

-- ---------------------------------------------------------------------------
-- stock_master: 종목 마스터
-- ---------------------------------------------------------------------------
CREATE TABLE stock_master (
    tck_cd  VARCHAR(10)  NOT NULL,
    tck_nm  VARCHAR(100) NOT NULL,
    mkt_cd  VARCHAR(10)  NOT NULL,
    reg_ts  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stock_master PRIMARY KEY (tck_cd),
    CONSTRAINT ck_stock_master_mkt_cd CHECK (mkt_cd IN ('KOSPI', 'KOSDAQ'))
);
CREATE INDEX idx_stock_mst_01 ON stock_master (tck_nm);

-- ---------------------------------------------------------------------------
-- price_minute: 시세 분봉
-- ---------------------------------------------------------------------------
CREATE TABLE price_minute (
    tck_cd     VARCHAR(10)    NOT NULL,
    bs_mi      VARCHAR(12)    NOT NULL,
    open_prc   NUMERIC(15, 0) NOT NULL,
    high_prc   NUMERIC(15, 0) NOT NULL,
    low_prc    NUMERIC(15, 0) NOT NULL,
    close_prc  NUMERIC(15, 0) NOT NULL,
    trd_vol    BIGINT         NOT NULL,
    reg_ts     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_price_minute PRIMARY KEY (tck_cd, bs_mi),
    CONSTRAINT fk_price_minute_tck_cd FOREIGN KEY (tck_cd) REFERENCES stock_master (tck_cd),
    CONSTRAINT ck_price_minute_trd_vol CHECK (trd_vol >= 0)
);

-- ---------------------------------------------------------------------------
-- news: 뉴스 기사 및 AI 분석 결과
-- ---------------------------------------------------------------------------
CREATE TABLE news (
    news_id       VARCHAR(50)   NOT NULL,
    news_titl     VARCHAR(500)  NOT NULL,
    news_cont     TEXT          NOT NULL,
    ai_smry_cont  VARCHAR(1000),
    ai_sntm_scr   NUMERIC(3, 2),
    pub_ts        TIMESTAMP     NOT NULL,
    reg_ts        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_news PRIMARY KEY (news_id)
);
CREATE INDEX idx_news_01 ON news (pub_ts DESC);

-- ---------------------------------------------------------------------------
-- news_stock: 뉴스-종목 N:M 매핑
-- ---------------------------------------------------------------------------
CREATE TABLE news_stock (
    news_id  VARCHAR(50) NOT NULL,
    tck_cd   VARCHAR(10) NOT NULL,
    reg_ts   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_news_stock PRIMARY KEY (news_id, tck_cd),
    CONSTRAINT fk_news_stock_news_id FOREIGN KEY (news_id) REFERENCES news (news_id),
    CONSTRAINT fk_news_stock_tck_cd FOREIGN KEY (tck_cd) REFERENCES stock_master (tck_cd)
);

-- ---------------------------------------------------------------------------
-- orders: 주문 (체결·거절 결과를 1회 저장) - INSERT 전용
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
    ord_no       VARCHAR(30)    NOT NULL,
    acnt_no      VARCHAR(20)    NOT NULL,
    tck_cd       VARCHAR(10)    NOT NULL,
    ord_dv_cd    VARCHAR(10)    NOT NULL,
    ord_qty      BIGINT         NOT NULL,
    ord_prc      NUMERIC(15, 0),
    prc_ts       TIMESTAMP,
    ord_stat_cd  VARCHAR(10)    NOT NULL,
    rjct_rsn_cd  VARCHAR(30),
    idmp_key     VARCHAR(36)    NOT NULL,
    conn_ip      VARCHAR(45)    NOT NULL,
    dvc_id       VARCHAR(64),
    user_agt     VARCHAR(255)   NOT NULL,
    trc_id       VARCHAR(36)    NOT NULL,
    reg_ts       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_orders PRIMARY KEY (ord_no),
    CONSTRAINT fk_orders_acnt_no FOREIGN KEY (acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT fk_orders_tck_cd FOREIGN KEY (tck_cd) REFERENCES stock_master (tck_cd),
    CONSTRAINT uk_orders_01 UNIQUE (acnt_no, idmp_key),
    CONSTRAINT ck_orders_ord_qty CHECK (ord_qty BETWEEN 1 AND 1000000),
    -- 테이블 정의서는 (ord_stat_cd <> 'FILLED' OR ord_prc > 0) 이지만, 그대로면 ord_prc가 NULL인 체결이 통과한다.
    -- NULL도 막도록 보완 (docs/decisions.md #5)
    CONSTRAINT ck_orders_filled_prc CHECK (ord_stat_cd <> 'FILLED' OR (ord_prc IS NOT NULL AND ord_prc > 0)),
    CONSTRAINT ck_orders_prc_ts CHECK ((ord_prc IS NULL) = (prc_ts IS NULL)),
    CONSTRAINT ck_orders_rjct_rsn CHECK ((ord_stat_cd = 'REJECTED') = (rjct_rsn_cd IS NOT NULL)),
    CONSTRAINT ck_orders_ord_dv_cd CHECK (ord_dv_cd IN ('BUY', 'SELL')),
    CONSTRAINT ck_orders_ord_stat_cd CHECK (ord_stat_cd IN ('FILLED', 'REJECTED')),
    CONSTRAINT ck_orders_rjct_rsn_cd CHECK (rjct_rsn_cd IN (
        'ACCOUNT_LOCKED', 'MARKET_CLOSED', 'PRICE_STALE', 'INSUFFICIENT_BALANCE', 'INSUFFICIENT_HOLDING'))
);
CREATE INDEX idx_orders_01 ON orders (acnt_no, reg_ts DESC);
CREATE INDEX idx_orders_02 ON orders (trc_id);

-- ---------------------------------------------------------------------------
-- trade_history: 원장 거래명세 (체결 1건당 1행, 거래 후 잔액 스냅샷) - INSERT 전용
-- ---------------------------------------------------------------------------
CREATE TABLE trade_history (
    trd_seq       BIGSERIAL      NOT NULL,
    ord_no        VARCHAR(30)    NOT NULL,
    acnt_no       VARCHAR(20)    NOT NULL,
    tck_cd        VARCHAR(10)    NOT NULL,
    trd_dv_cd     VARCHAR(10)    NOT NULL,
    trd_qty       BIGINT         NOT NULL,
    trd_prc       NUMERIC(15, 0) NOT NULL,
    trd_amt       NUMERIC(19, 0) NOT NULL,
    post_dps_bal  NUMERIC(19, 0) NOT NULL,
    post_hld_qty  BIGINT         NOT NULL,
    reg_ts        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_trade_history PRIMARY KEY (trd_seq),
    CONSTRAINT fk_trade_history_ord_no FOREIGN KEY (ord_no) REFERENCES orders (ord_no),
    CONSTRAINT fk_trade_history_acnt_no FOREIGN KEY (acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT uk_trade_01 UNIQUE (ord_no),
    CONSTRAINT ck_trade_history_trd_qty CHECK (trd_qty > 0),
    CONSTRAINT ck_trade_history_trd_prc CHECK (trd_prc > 0),
    CONSTRAINT ck_trade_history_trd_dv_cd CHECK (trd_dv_cd IN ('BUY', 'SELL'))
);
CREATE INDEX idx_trade_01 ON trade_history (acnt_no, reg_ts DESC);

-- ---------------------------------------------------------------------------
-- holding: 원장 보유종목 잔고 (계좌 락 안에서만 변경)
-- ---------------------------------------------------------------------------
CREATE TABLE holding (
    acnt_no      VARCHAR(20)    NOT NULL,
    tck_cd       VARCHAR(10)    NOT NULL,
    hld_qty      BIGINT         NOT NULL DEFAULT 0,
    avg_buy_prc  NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    reg_ts       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_ts       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_holding PRIMARY KEY (acnt_no, tck_cd),
    CONSTRAINT fk_holding_acnt_no FOREIGN KEY (acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT fk_holding_tck_cd FOREIGN KEY (tck_cd) REFERENCES stock_master (tck_cd),
    CONSTRAINT ck_holding_hld_qty CHECK (hld_qty >= 0)
);

-- ---------------------------------------------------------------------------
-- withdrawal: 모의 출금 (실제 이체 없음, 결과를 1회 저장) - INSERT 전용
-- ---------------------------------------------------------------------------
CREATE TABLE withdrawal (
    wdr_no             VARCHAR(30)    NOT NULL,
    acnt_no            VARCHAR(20)    NOT NULL,
    wdr_amt            NUMERIC(19, 0) NOT NULL,
    rcv_bank_nm        VARCHAR(50)    NOT NULL,
    rcv_acnt_no        VARCHAR(30)    NOT NULL,
    add_auth_yn        CHAR(1)        NOT NULL DEFAULT 'N',
    post_pwd_fail_cnt  INTEGER,
    wdr_stat_cd        VARCHAR(10)    NOT NULL,
    rjct_rsn_cd        VARCHAR(30),
    post_dps_bal       NUMERIC(19, 0),
    idmp_key           VARCHAR(36)    NOT NULL,
    conn_ip            VARCHAR(45)    NOT NULL,
    dvc_id             VARCHAR(64),
    user_agt           VARCHAR(255)   NOT NULL,
    trc_id             VARCHAR(36)    NOT NULL,
    reg_ts             TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_withdrawal PRIMARY KEY (wdr_no),
    CONSTRAINT fk_withdrawal_acnt_no FOREIGN KEY (acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT uk_wdr_01 UNIQUE (acnt_no, idmp_key),
    CONSTRAINT ck_withdrawal_wdr_amt CHECK (wdr_amt > 0),
    CONSTRAINT ck_withdrawal_post_pwd_fail_cnt_yn CHECK ((add_auth_yn = 'Y') = (post_pwd_fail_cnt IS NOT NULL)),
    CONSTRAINT ck_withdrawal_post_pwd_fail_cnt CHECK (post_pwd_fail_cnt BETWEEN 0 AND 5),
    CONSTRAINT ck_withdrawal_rjct_rsn CHECK ((wdr_stat_cd = 'REJECTED') = (rjct_rsn_cd IS NOT NULL)),
    CONSTRAINT ck_withdrawal_post_dps_bal CHECK ((wdr_stat_cd = 'DONE') = (post_dps_bal IS NOT NULL)),
    CONSTRAINT ck_withdrawal_add_auth_yn CHECK (add_auth_yn IN ('Y', 'N')),
    CONSTRAINT ck_withdrawal_wdr_stat_cd CHECK (wdr_stat_cd IN ('DONE', 'REJECTED')),
    CONSTRAINT ck_withdrawal_rjct_rsn_cd CHECK (rjct_rsn_cd IN (
        'ACCOUNT_LOCKED', 'INSUFFICIENT_BALANCE', 'WDR_PWD_MISMATCH', 'WDR_PWD_LOCKED'))
);
CREATE INDEX idx_wdr_01 ON withdrawal (acnt_no, reg_ts DESC);
-- 24시간 누적 출금액 계산용 (완료 건만)
CREATE INDEX idx_wdr_02 ON withdrawal (acnt_no, reg_ts) WHERE wdr_stat_cd = 'DONE';
CREATE INDEX idx_wdr_03 ON withdrawal (trc_id);

-- ---------------------------------------------------------------------------
-- fds_detection: FDS 탐지 기록 (규칙 1~3) - 삭제 불가, 결과·출금번호·AI 설명·확인 여부만 갱신
-- ---------------------------------------------------------------------------
CREATE TABLE fds_detection (
    dtc_seq       BIGSERIAL     NOT NULL,
    rule_cd       VARCHAR(30)   NOT NULL,
    mbr_no        BIGINT        NOT NULL,
    acnt_no       VARCHAR(20),
    ord_no        VARCHAR(30),
    wdr_no        VARCHAR(30),
    req_idmp_key  VARCHAR(36),
    conn_ip       VARCHAR(45)   NOT NULL,
    dvc_id        VARCHAR(64),
    user_agt      VARCHAR(255)  NOT NULL,
    rslt_cd       VARCHAR(10)   NOT NULL,
    dtl_data      JSONB         NOT NULL,
    ai_rsn_cont   VARCHAR(1000),
    ai_chk_cont   VARCHAR(1000),
    ai_stat_cd    VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED',
    chk_yn        CHAR(1)       NOT NULL DEFAULT 'N',
    reg_ts        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_ts        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_fds_detection PRIMARY KEY (dtc_seq),
    CONSTRAINT fk_fds_detection_mbr_no FOREIGN KEY (mbr_no) REFERENCES member (mbr_no),
    CONSTRAINT fk_fds_detection_acnt_no FOREIGN KEY (acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT fk_fds_detection_ord_no FOREIGN KEY (ord_no) REFERENCES orders (ord_no),
    CONSTRAINT fk_fds_detection_wdr_no FOREIGN KEY (wdr_no) REFERENCES withdrawal (wdr_no),
    CONSTRAINT ck_fds_detection_rule_cd CHECK (rule_cd IN ('LOGIN_NEW_ACCESS', 'WITHDRAW_LARGE', 'TRADE_NEW_IP')),
    CONSTRAINT ck_fds_detection_rslt_cd CHECK (rslt_cd IN ('PENDING', 'PASS', 'FAIL', 'LOCKED', 'LOGGED')),
    CONSTRAINT ck_fds_detection_ai_stat_cd CHECK (ai_stat_cd IN ('REQUESTED', 'DONE', 'FAILED')),
    CONSTRAINT ck_fds_detection_chk_yn CHECK (chk_yn IN ('Y', 'N'))
);
CREATE INDEX idx_fds_dtc_01 ON fds_detection (rule_cd, reg_ts DESC);
CREATE INDEX idx_fds_dtc_02 ON fds_detection (mbr_no, reg_ts DESC);
CREATE INDEX idx_fds_dtc_03 ON fds_detection (chk_yn);
CREATE INDEX idx_fds_dtc_04 ON fds_detection (acnt_no, req_idmp_key);

-- ---------------------------------------------------------------------------
-- fds_action_log: FDS 조치 이력 - INSERT 전용 감사 로그
-- ---------------------------------------------------------------------------
CREATE TABLE fds_action_log (
    act_log_seq      BIGSERIAL    NOT NULL,
    tgt_dtc_seq      BIGINT,
    tgt_acnt_no      VARCHAR(20),
    act_dv_cd        VARCHAR(20)  NOT NULL,
    proc_mbr_no      BIGINT,
    act_rsn_cont     VARCHAR(500) NOT NULL,
    act_actor_dv_cd  VARCHAR(10)  NOT NULL,
    reg_ts           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_fds_action_log PRIMARY KEY (act_log_seq),
    CONSTRAINT fk_fds_action_log_tgt_dtc_seq FOREIGN KEY (tgt_dtc_seq) REFERENCES fds_detection (dtc_seq),
    CONSTRAINT fk_fds_action_log_tgt_acnt_no FOREIGN KEY (tgt_acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT fk_fds_action_log_proc_mbr_no FOREIGN KEY (proc_mbr_no) REFERENCES member (mbr_no),
    CONSTRAINT ck_fds_action_log_act_dv_cd CHECK (act_dv_cd IN ('TEMP_LOCK', 'LOCK', 'UNLOCK', 'CHECK')),
    CONSTRAINT ck_fds_action_log_act_actor_dv_cd CHECK (act_actor_dv_cd IN ('SYSTEM', 'ADMIN'))
);
CREATE INDEX idx_act_log_01 ON fds_action_log (tgt_dtc_seq);
CREATE INDEX idx_act_log_02 ON fds_action_log (tgt_acnt_no);

-- ---------------------------------------------------------------------------
-- reconcile_run: 원장 대사 실행 이력 (실행 1회 = 1행)
-- ---------------------------------------------------------------------------
CREATE TABLE reconcile_run (
    rccl_run_no    BIGSERIAL    NOT NULL,
    rccl_dt        VARCHAR(8)   NOT NULL,
    trg_dv_cd      VARCHAR(10)  NOT NULL,
    proc_mbr_no    BIGINT,
    run_stat_cd    VARCHAR(10)  NOT NULL DEFAULT 'RUNNING',
    tot_acnt_cnt   INTEGER,
    diff_acnt_cnt  INTEGER,
    fail_rsn_cont  VARCHAR(500),
    reg_ts         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_ts         TIMESTAMP,
    CONSTRAINT pk_reconcile_run PRIMARY KEY (rccl_run_no),
    CONSTRAINT fk_reconcile_run_proc_mbr_no FOREIGN KEY (proc_mbr_no) REFERENCES member (mbr_no),
    CONSTRAINT ck_reconcile_run_done_cnt CHECK ((run_stat_cd = 'DONE') = (tot_acnt_cnt IS NOT NULL AND diff_acnt_cnt IS NOT NULL)),
    CONSTRAINT ck_reconcile_run_fail_rsn CHECK ((run_stat_cd = 'FAILED') = (fail_rsn_cont IS NOT NULL)),
    CONSTRAINT ck_reconcile_run_end_ts CHECK ((run_stat_cd = 'RUNNING') = (end_ts IS NULL)),
    CONSTRAINT ck_reconcile_run_trg_dv_cd CHECK (trg_dv_cd IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_reconcile_run_run_stat_cd CHECK (run_stat_cd IN ('RUNNING', 'DONE', 'FAILED'))
);
CREATE INDEX idx_rccl_run_01 ON reconcile_run (rccl_dt DESC);
-- 실행 중(RUNNING)은 동시에 1건만 허용
CREATE UNIQUE INDEX uk_rccl_run_01 ON reconcile_run (run_stat_cd) WHERE run_stat_cd = 'RUNNING';

-- ---------------------------------------------------------------------------
-- reconcile_result: 원장 대사 결과 (계좌 x 점검 항목, 일치 건 포함) - INSERT 전용
-- ---------------------------------------------------------------------------
CREATE TABLE reconcile_result (
    rslt_seq     BIGSERIAL      NOT NULL,
    rccl_run_no  BIGINT         NOT NULL,
    acnt_no      VARCHAR(20)    NOT NULL,
    chk_item_cd  VARCHAR(10)    NOT NULL,
    tck_cd       VARCHAR(10),
    exp_val      NUMERIC(19, 0) NOT NULL,
    actl_val     NUMERIC(19, 0) NOT NULL,
    diff_yn      CHAR(1)        NOT NULL,
    reg_ts       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_reconcile_result PRIMARY KEY (rslt_seq),
    CONSTRAINT fk_reconcile_result_rccl_run_no FOREIGN KEY (rccl_run_no) REFERENCES reconcile_run (rccl_run_no),
    CONSTRAINT fk_reconcile_result_acnt_no FOREIGN KEY (acnt_no) REFERENCES account (acnt_no),
    CONSTRAINT uk_rccl_rslt_01 UNIQUE NULLS NOT DISTINCT (rccl_run_no, acnt_no, chk_item_cd, tck_cd),
    CONSTRAINT ck_reconcile_result_tck_cd CHECK ((chk_item_cd = 'HOLDING') = (tck_cd IS NOT NULL)),
    CONSTRAINT ck_reconcile_result_chk_item_cd CHECK (chk_item_cd IN ('DEPOSIT', 'HOLDING')),
    CONSTRAINT ck_reconcile_result_diff_yn CHECK (diff_yn IN ('Y', 'N'))
);
CREATE INDEX idx_rccl_rslt_01 ON reconcile_result (rccl_run_no, diff_yn);
