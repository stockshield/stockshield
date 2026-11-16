-- 부하 테스트 뒤 loadtest 계좌의 원장이 맞는지 확인. 원장 대사와 같은 기준 (ADM_REC_001 #1, 테이블 정의서 '공통 규칙' 12·13행)
-- 예수금 = 초기 금액 − 매수 체결금액 + 매도 체결금액 − 완료 출금, 종목별 보유 수량 = 매수 − 매도
-- 실행(저장소 루트):
--   docker cp backend\load-test\verify.sql stockshield-postgres:/tmp/verify.sql
--   docker exec stockshield-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -f /tmp/verify.sql'

\echo '[1] 주문·출금 건수(상태별)'
SELECT 'orders' AS tbl, o.ord_stat_cd AS stat, coalesce(o.rjct_rsn_cd, '-') AS rjct, count(*) AS cnt
FROM orders o
JOIN account a ON a.acnt_no = o.acnt_no
JOIN member m ON m.mbr_no = a.mbr_no
WHERE m.lgn_id LIKE 'loadtest__'
GROUP BY o.ord_stat_cd, o.rjct_rsn_cd
UNION ALL
SELECT 'withdrawal', w.wdr_stat_cd, coalesce(w.rjct_rsn_cd, '-'), count(*)
FROM withdrawal w
JOIN account a ON a.acnt_no = w.acnt_no
JOIN member m ON m.mbr_no = a.mbr_no
WHERE m.lgn_id LIKE 'loadtest__'
GROUP BY w.wdr_stat_cd, w.rjct_rsn_cd
ORDER BY 1, 2, 3;

\echo '[2] 예수금 불일치 계좌 수 (0이어야 함)'
WITH acc AS (
    SELECT a.acnt_no, a.init_dps_amt, a.dps_bal
    FROM account a
    JOIN member m ON m.mbr_no = a.mbr_no
    WHERE m.lgn_id LIKE 'loadtest__'
), trd AS (
    SELECT t.acnt_no,
           sum(CASE WHEN t.trd_dv_cd = 'BUY' THEN -t.trd_amt ELSE t.trd_amt END) AS net_amt
    FROM trade_history t
    WHERE t.acnt_no IN (SELECT acnt_no FROM acc)
    GROUP BY t.acnt_no
), wdr AS (
    SELECT w.acnt_no, sum(w.wdr_amt) AS done_amt
    FROM withdrawal w
    WHERE w.wdr_stat_cd = 'DONE' AND w.acnt_no IN (SELECT acnt_no FROM acc)
    GROUP BY w.acnt_no
)
SELECT count(*) AS accounts,
       count(*) FILTER (WHERE acc.dps_bal
           <> acc.init_dps_amt + coalesce(trd.net_amt, 0) - coalesce(wdr.done_amt, 0)) AS dps_mismatch,
       sum(acc.dps_bal) AS total_dps_bal
FROM acc
LEFT JOIN trd ON trd.acnt_no = acc.acnt_no
LEFT JOIN wdr ON wdr.acnt_no = acc.acnt_no;

\echo '[3] 보유 수량 불일치 (행이 없어야 함)'
WITH acc AS (
    SELECT a.acnt_no
    FROM account a
    JOIN member m ON m.mbr_no = a.mbr_no
    WHERE m.lgn_id LIKE 'loadtest__'
), trd AS (
    SELECT t.acnt_no, t.tck_cd,
           sum(CASE WHEN t.trd_dv_cd = 'BUY' THEN t.trd_qty ELSE -t.trd_qty END) AS net_qty
    FROM trade_history t
    WHERE t.acnt_no IN (SELECT acnt_no FROM acc)
    GROUP BY t.acnt_no, t.tck_cd
), hld AS (
    SELECT h.acnt_no, h.tck_cd, h.hld_qty
    FROM holding h
    WHERE h.acnt_no IN (SELECT acnt_no FROM acc)
)
SELECT coalesce(trd.acnt_no, hld.acnt_no) AS acnt_no,
       coalesce(trd.tck_cd, hld.tck_cd) AS tck_cd,
       trd.net_qty AS trade_qty,
       hld.hld_qty AS holding_qty
FROM trd
FULL OUTER JOIN hld ON hld.acnt_no = trd.acnt_no AND hld.tck_cd = trd.tck_cd
WHERE trd.net_qty IS DISTINCT FROM hld.hld_qty;

\echo '[4] 체결마다 거래명세 1건 (orphan_* 모두 0이어야 함)'
SELECT count(*) FILTER (WHERE t.ord_no IS NULL) AS orphan_filled_without_trade,
       (SELECT count(*)
        FROM trade_history t2
        JOIN orders o2 ON o2.ord_no = t2.ord_no
        JOIN account a2 ON a2.acnt_no = t2.acnt_no
        JOIN member m2 ON m2.mbr_no = a2.mbr_no
        WHERE m2.lgn_id LIKE 'loadtest__' AND o2.ord_stat_cd <> 'FILLED') AS orphan_trade_not_filled
FROM orders o
JOIN account a ON a.acnt_no = o.acnt_no
JOIN member m ON m.mbr_no = a.mbr_no
LEFT JOIN trade_history t ON t.ord_no = o.ord_no
WHERE m.lgn_id LIKE 'loadtest__' AND o.ord_stat_cd = 'FILLED';

\echo '[5] loadtest 회원의 FDS 탐지 (TRADE_NEW_IP·WITHDRAW_LARGE가 없어야 함)'
SELECT d.rule_cd, d.rslt_cd, count(*) AS cnt
FROM fds_detection d
JOIN member m ON m.mbr_no = d.mbr_no
WHERE m.lgn_id LIKE 'loadtest__'
GROUP BY d.rule_cd, d.rslt_cd
ORDER BY 1, 2;
