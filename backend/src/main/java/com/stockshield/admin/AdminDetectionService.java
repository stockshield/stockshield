package com.stockshield.admin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.common.web.KstTime;
import com.stockshield.common.web.PageBody;
import com.stockshield.fds.api.FdsService;
import com.stockshield.ledger.FdsActionLog;
import com.stockshield.ledger.FdsActionLogRepository;

/**
 * 관리자 탐지 목록·상세·확인 (FDS_ALT_001 #2·#3·#4·#6, API 명세서 'API_명세서' 23~25행).
 * fds_detection은 FDS(윤제정)만 씀. 여기서는 읽기만 하고, 확인 표시는 FdsService.markChecked로 함 (연결 합의서 4·8절).
 */
@Service
public class AdminDetectionService {

    static final Set<String> RULE_CODES = Set.of("LOGIN_NEW_ACCESS", "WITHDRAW_LARGE", "TRADE_NEW_IP");
    private static final Set<String> YN = Set.of("Y", "N");

    private final JdbcTemplate jdbcTemplate;
    private final FdsService fdsService;
    private final FdsActionLogRepository actionLogs;
    private final ObjectMapper objectMapper;

    public AdminDetectionService(JdbcTemplate jdbcTemplate, FdsService fdsService,
                                 FdsActionLogRepository actionLogs, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.fdsService = fdsService;
        this.actionLogs = actionLogs;
        this.objectMapper = objectMapper;
    }

    /** 최신순. 같은 시각이면 나중 번호가 먼저. 걸러 보기 값이 코드 정의 밖이면 400 */
    @Transactional(readOnly = true)
    public PageBody list(String ruleCd, String chkYn, int page, int size) {
        if ((ruleCd != null && !RULE_CODES.contains(ruleCd)) || (chkYn != null && !YN.contains(chkYn))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageBody.request(page, size, Sort.unsorted());
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ruleCd != null) {
            where.append(" AND d.rule_cd = ?");
            args.add(ruleCd);
        }
        if (chkYn != null) {
            where.append(" AND d.chk_yn = ?");
            args.add(chkYn);
        }
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fds_detection d" + where, Long.class,
                args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<Map<String, Object>> content = jdbcTemplate.query("""
                SELECT d.dtc_seq, d.rule_cd, m.lgn_id, d.acnt_no, d.rslt_cd, d.chk_yn, d.reg_ts
                  FROM fds_detection d
                  JOIN member m ON m.mbr_no = d.mbr_no
                """ + where + " ORDER BY d.reg_ts DESC, d.dtc_seq DESC LIMIT ? OFFSET ?", (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dtcSeq", rs.getLong("dtc_seq"));
            item.put("ruleCd", rs.getString("rule_cd"));
            item.put("mskLgnId", Masking.lgnId(rs.getString("lgn_id")));
            item.put("acntNo", rs.getString("acnt_no"));
            item.put("rsltCd", rs.getString("rslt_cd"));
            item.put("chkYn", rs.getString("chk_yn"));
            item.put("regTs", KstTime.format(rs.getObject("reg_ts", LocalDateTime.class)));
            return item;
        }, pageArgs.toArray());
        return PageBody.of(new PageImpl<>(content, pageable, total), content);
    }

    /** acntStatCd는 조회 시점 계좌 상태(계좌가 없는 탐지는 null) (문서 수정 대기 목록 #12) */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(long dtcSeq) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT d.*, a.acnt_stat_cd
                  FROM fds_detection d
                  LEFT JOIN account a ON a.acnt_no = d.acnt_no
                 WHERE d.dtc_seq = ?
                """, (rs, n) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dtcSeq", rs.getLong("dtc_seq"));
            body.put("ruleCd", rs.getString("rule_cd"));
            body.put("rsltCd", rs.getString("rslt_cd"));
            body.put("acntNo", rs.getString("acnt_no"));
            body.put("ordNo", rs.getString("ord_no"));
            body.put("wdrNo", rs.getString("wdr_no"));
            body.put("dtlData", json(rs.getString("dtl_data")));
            body.put("aiRsnCont", rs.getString("ai_rsn_cont"));
            body.put("aiChkCont", rs.getString("ai_chk_cont"));
            body.put("aiStatCd", rs.getString("ai_stat_cd"));
            body.put("mskConnIp", Masking.connIp(rs.getString("conn_ip")));
            body.put("userAgt", rs.getString("user_agt"));
            body.put("chkYn", rs.getString("chk_yn"));
            body.put("regTs", KstTime.format(rs.getObject("reg_ts", LocalDateTime.class)));
            body.put("acntStatCd", rs.getString("acnt_stat_cd"));
            return body;
        }, dtcSeq);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.DETECTION_NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 확인 표시를 먼저 하고 바뀌었을 때만 CHECK 이력을 남김. 둘이 한 커밋이라 동시에 확인해도 한 번만 성공하고 이력도 하나 (연결 합의서 8절).
     * markChecked는 MANDATORY라 이 트랜잭션 밖에서는 부를 수 없음.
     */
    @Transactional
    public Map<String, Object> check(long adminMbrNo, long dtcSeq, String actRsnCont) {
        String reason = ActionReasons.normalize(actRsnCont);
        switch (fdsService.markChecked(dtcSeq)) {
            case CHECKED -> actionLogs.saveAndFlush(FdsActionLog.check(dtcSeq, adminMbrNo, reason));
            case ALREADY_CHECKED -> throw new ApiException(ErrorCode.ALREADY_CHECKED);
            case NOT_FOUND -> throw new ApiException(ErrorCode.DETECTION_NOT_FOUND);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dtcSeq", dtcSeq);
        body.put("chkYn", "Y");
        return body;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("탐지 상세 내용을 읽지 못함", e);
        }
    }
}
