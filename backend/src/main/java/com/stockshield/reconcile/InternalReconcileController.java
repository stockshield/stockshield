package com.stockshield.reconcile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;

/**
 * GitHub Actions(reconcile.yml)가 평일 16:30에 그날 정기 대사 결과를 확인하는 내부 API (API 명세서 'API_명세서' 31행).
 * JWT 대상이 아님(SecurityConfig가 /api/v1/internal/**을 토큰 검사 밖에 둠). 토큰은 여기서 확인함.
 */
@RestController
public class InternalReconcileController {

    private static final Pattern EIGHT_DIGITS = Pattern.compile("^[0-9]{8}$");
    private static final DateTimeFormatter STRICT_YYYYMMDD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private final ReconcileService reconcileService;
    private final InternalTokenVerifier tokenVerifier;

    public InternalReconcileController(ReconcileService reconcileService, InternalTokenVerifier tokenVerifier) {
        this.reconcileService = reconcileService;
        this.tokenVerifier = tokenVerifier;
    }

    /** 토큰을 먼저 봄. 토큰 없는 요청에는 날짜 형식 검사 결과도 알려 주지 않음 */
    @GetMapping("/api/v1/internal/reconcile/status")
    public Map<String, Object> status(@RequestHeader(name = "X-Internal-Token", required = false) String token,
                                      @RequestParam(name = "rcclDt", required = false) String rcclDt) {
        if (!tokenVerifier.matches(token)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String date = rcclDt == null ? reconcileService.today() : validDate(rcclDt);
        return reconcileService.scheduledStatus(date);
    }

    private static String validDate(String rcclDt) {
        if (!EIGHT_DIGITS.matcher(rcclDt).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        try {
            LocalDate.parse(rcclDt, STRICT_YYYYMMDD);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return rcclDt;
    }
}
