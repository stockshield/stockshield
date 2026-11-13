package com.stockshield.fds.access;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.stockshield.fds.api.ConfirmResult;
import com.stockshield.fds.api.LoginAccessCommand;
import com.stockshield.fds.api.LoginAccessResult;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.detection.DetectionRecorder;
import com.stockshield.fds.detection.DetectionRecorder.ConfirmedLogin;
import com.stockshield.fds.detection.DetectionRecorder.NewDetection;
import com.stockshield.fds.detection.DetectionResult;
import com.stockshield.fds.detection.DetectionRule;
import com.stockshield.fds.access.TrustedAccessStore.RegPath;

/**
 * FDS ① 새 접속 본인 인증 (FDS_LGN_001, 연결 합의서 v0.0.8 5절).
 *
 * FDS는 새 접속인지 판단하고 탐지 기록·신뢰 접속 등록만 한다. 인증 코드 발송·확인·재발송과 토큰 발급은 로그인 쪽(조성우)이 하고,
 * 그 결과만 confirm·fail로 알려 준다. DB만 쓰고 Redis·메일·외부 HTTP를 부르지 않는다(합의 3·5-2).
 */
@Component
public class LoginAccessChecker {

    private final TrustedAccessStore trustedAccess;
    private final DetectionRecorder recorder;

    public LoginAccessChecker(TrustedAccessStore trustedAccess, DetectionRecorder recorder) {
        this.trustedAccess = trustedAccess;
        this.recorder = recorder;
    }

    /**
     * IP와 기기를 각각 찾아 둘 다 있으면 KNOWN(같은 행이 있으면 그 행의 last_use_ts 갱신).
     * 아니면 LOGIN_NEW_ACCESS 탐지 기록을 PENDING으로 저장하고 NEW_ACCESS(dtcSeq, reg_ts + 5분).
     * User-Agent는 브라우저 업데이트마다 바뀌어 판단에 쓰지 않고 기록만 한다(FDS_LGN_001 #1).
     */
    public LoginAccessResult check(LoginAccessCommand cmd) {
        long mbrNo = cmd.mbrNo();
        RequestMeta meta = cmd.meta();
        boolean knownIp = trustedAccess.isKnownIp(mbrNo, meta.connIp());
        boolean knownDevice = trustedAccess.isKnownDevice(mbrNo, meta.dvcId());
        if (knownIp && knownDevice) {
            trustedAccess.touch(mbrNo, meta);
            return new LoginAccessResult(LoginAccessResult.Type.KNOWN, null, null);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("newIp", !knownIp);
        detail.put("newDevice", !knownDevice);
        DetectionRecorder.Saved saved = recorder.save(new NewDetection(DetectionRule.LOGIN_NEW_ACCESS, mbrNo,
                null, null, null, null, meta, DetectionResult.PENDING, detail));
        return new LoginAccessResult(LoginAccessResult.Type.NEW_ACCESS, saved.dtcSeq(), saved.expiresAt());
    }

    /**
     * 코드가 맞았다는 알림. PENDING이고 5분 안이면 PASS로 바꾸고, 로그인 때 기록한 회원·IP·기기·User-Agent를
     * 신뢰 접속(EMAIL_AUTH)으로 등록한다. 이미 PENDING이 아니거나 만료됐으면 등록하지 않고 EXPIRED.
     */
    public ConfirmResult confirm(long dtcSeq) {
        Optional<ConfirmedLogin> confirmed = recorder.confirmLoginPending(dtcSeq);
        if (confirmed.isEmpty()) {
            return ConfirmResult.EXPIRED;
        }
        ConfirmedLogin c = confirmed.get();
        trustedAccess.register(c.mbrNo(), c.connIp(), c.dvcId(), c.userAgt(), RegPath.EMAIL_AUTH);
        return ConfirmResult.CONFIRMED;
    }

    /** 5회 오류·메일 발송 실패. PENDING이면 FAIL, 이미 아니면 아무것도 하지 않는다(예외 아님) */
    public void fail(long dtcSeq) {
        recorder.changePendingResult(dtcSeq, DetectionResult.FAIL, null);
    }

    /** 가입 때 첫 IP·기기 등록 (USR_AUTH_001 #4). 가입 트랜잭션 안에서 불린다 */
    public void registerSignup(long mbrNo, RequestMeta meta) {
        trustedAccess.register(mbrNo, meta.connIp(), meta.dvcId(), meta.userAgt(), RegPath.SIGNUP);
    }
}
