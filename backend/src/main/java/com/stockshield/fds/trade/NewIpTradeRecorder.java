package com.stockshield.fds.trade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.stockshield.fds.access.TrustedAccessStore;
import com.stockshield.fds.access.TrustedAccessStore.IpSummary;
import com.stockshield.fds.api.FilledOrderView;
import com.stockshield.fds.api.LedgerReader;
import com.stockshield.fds.api.OrderFilledEvent;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.config.FdsExecutorConfig;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.detection.DetectionRecorder.NewDetection;
import com.stockshield.fds.detection.DetectionResult;
import com.stockshield.fds.detection.DetectionRule;

/**
 * FDS ③ 새 IP 직후 거래 기록 (FDS_TRD_001, 연결 합의서 v0.0.8 7절).
 *
 * 원장이 체결 주문 트랜잭션 안에서 OrderFilledEvent를 발행하면, 커밋 뒤 fdsExecutor 스레드의 새 트랜잭션에서
 * 주문번호로 주문을 다시 읽어 판단한다. 주문 응답과 결과는 이 기록의 영향을 받지 않는다(부록 #18).
 *
 * 판단(주문 시각 FilledOrderView.regTs 기준, 비동기라 실행이 늦어도 같은 판단):
 * - 주문 IP가 그 회원의 신뢰 목록에 없거나 주문 뒤에 등록됐으면: IP_NOT_TRUSTED (로그인 뒤 IP가 바뀐 경우)
 * - 그 IP에 가입(SIGNUP) 행이 있으면: 기록하지 않음
 * - 그 IP의 첫 인증 시각(같은 IP 행 중 가장 이른 reg_ts) ≤ 주문 시각 < 첫 인증 시각 + 24시간이면: NEW_IP_AFTER_AUTH
 * 결과는 LOGGED. 같은 주문번호의 기록이 이미 있으면 다시 저장하지 않는다.
 * 실패하면 로그만 남고 주문은 그대로다(FDS_TRD_001 #4). 실행기가 가득 차 거부된 작업도 로그만 남는다.
 */
@Component
public class NewIpTradeRecorder {

    /** 판단 사유 (dtl_data.reason) */
    public enum Reason {
        /** 이메일 인증으로 처음 등록된 IP의 24시간 안 거래 */
        NEW_IP_AFTER_AUTH,
        /** 신뢰 목록에 없는 IP의 거래 (로그인 뒤 IP가 바뀜) */
        IP_NOT_TRUSTED
    }

    static final Duration WINDOW = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(NewIpTradeRecorder.class);
    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+09:00'");
    private static final String ALREADY_RECORDED = """
            SELECT EXISTS (SELECT 1 FROM fds_detection WHERE ord_no = ? AND rule_cd = 'TRADE_NEW_IP')
            """;

    private final LedgerReader ledgerReader;
    private final TrustedAccessStore trustedAccess;
    private final DetectionRecorder recorder;
    private final JdbcTemplate jdbcTemplate;

    public NewIpTradeRecorder(LedgerReader ledgerReader, TrustedAccessStore trustedAccess,
                              DetectionRecorder recorder, JdbcTemplate jdbcTemplate) {
        this.ledgerReader = ledgerReader;
        this.trustedAccess = trustedAccess;
        this.recorder = recorder;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 체결 주문 커밋 뒤 실행. Spring 6.1부터 이 리스너에는 REQUIRES_NEW·NOT_SUPPORTED 외의 @Transactional을 붙일 수 없다.
     * 예외는 로그를 남기고 다시 던져 이 트랜잭션만 롤백한다(주문 쪽으로는 넘어가지 않는다).
     */
    @Async(FdsExecutorConfig.FDS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderFilled(OrderFilledEvent event) {
        try {
            record(event.ordNo());
        } catch (RuntimeException e) {
            log.warn("새 IP 거래 기록 실패, 주문은 그대로 둠 ordNo={}", event.ordNo(), e);
            throw e;
        }
    }

    /** 판단하고 필요하면 기록한다. 기록했으면 그 사유, 아니면 empty. 호출한 쪽 트랜잭션에서 돈다 */
    public Optional<Reason> record(String ordNo) {
        Optional<FilledOrderView> found = ledgerReader.findFilledOrder(ordNo);
        if (found.isEmpty()) {
            log.debug("체결 주문이 아니어서 새 IP 판단을 건너뜀 ordNo={}", ordNo);
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(ALREADY_RECORDED, Boolean.class, ordNo))) {
            return Optional.empty();
        }
        FilledOrderView order = found.get();
        IpSummary ip = trustedAccess.ipSummary(order.mbrNo(), order.connIp());
        Optional<Reason> reason = judge(ip, order.regTs());
        reason.ifPresent(r -> save(order, ip, r));
        return reason;
    }

    static Optional<Reason> judge(IpSummary ip, LocalDateTime orderTs) {
        if (!ip.trusted() || ip.firstRegTs().isAfter(orderTs)) {
            return Optional.of(Reason.IP_NOT_TRUSTED);
        }
        if (ip.hasSignup()) {
            return Optional.empty();
        }
        boolean withinWindow = orderTs.isBefore(ip.firstRegTs().plus(WINDOW));
        return withinWindow ? Optional.of(Reason.NEW_IP_AFTER_AUTH) : Optional.empty();
    }

    private void save(FilledOrderView order, IpSummary ip, Reason reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason.name());
        detail.put("ipFirstAuthTs", reason == Reason.NEW_IP_AFTER_AUTH ? ip.firstRegTs().format(KST) : null);
        detail.put("ordTs", order.regTs().format(KST));
        detail.put("tckCd", order.tckCd());
        detail.put("ordDvCd", order.ordDvCd());
        detail.put("trdAmt", order.trdAmt());
        recorder.insert(new NewDetection(DetectionRule.TRADE_NEW_IP, order.mbrNo(), order.acntNo(), order.ordNo(),
                null, null, new RequestMeta(order.connIp(), order.dvcId(), order.userAgt()),
                DetectionResult.LOGGED, detail));
    }
}
