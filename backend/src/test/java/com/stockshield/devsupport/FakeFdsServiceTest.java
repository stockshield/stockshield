package com.stockshield.devsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockshield.fds.api.FdsService;
import com.stockshield.fds.api.LockedAccount;
import com.stockshield.fds.api.RequestMeta;
import com.stockshield.fds.api.WithdrawCheckResult.Decision;
import com.stockshield.fds.api.WithdrawCommand;
import com.stockshield.support.IntegrationTestSupport;

/**
 * 기동용 가짜 FdsService(fake-fds 프로필). 연결 합의서 v0.0.8 2절·12절
 * - FdsService 인터페이스에 선언한 전파(MANDATORY)가 가짜에도 걸린다: 가짜로 돌린 원장 코드도 트랜잭션 밖 호출이면 실패한다
 * - 대량 출금이면 428(PASSWORD_REQUIRED), 비밀번호를 붙이면 통과: 프론트가 11/16 전에 출금 비밀번호 입력 창을 확인할 수 있다
 * - prod 프로필과 함께 켜면 기동을 멈춘다
 */
@ActiveProfiles("fake-fds")
class FakeFdsServiceTest extends IntegrationTestSupport {

    private static final LockedAccount ACCOUNT = new LockedAccount("100000000010", 1L, "hash", 0);

    @Autowired
    FdsService fdsService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("인터페이스에 선언한 MANDATORY는 가짜에도 걸려, 트랜잭션 밖에서 부르면 실패한다")
    void mandatoryDeclaredOnInterfaceAppliesToFake() {
        assertThat(fdsService).isInstanceOf(FakeFdsService.class);

        assertThatThrownBy(() -> fdsService.checkWithdrawal(ACCOUNT, command(5_000_000, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fdsService.markChecked(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("기준 금액 미만은 NOT_LARGE, 이상이면 비밀번호가 없을 때 PASSWORD_REQUIRED, 있을 때 PASSED")
    void largeWithdrawalAsksForPasswordAtThreshold() {
        assertThat(check(4_999_999, null)).isEqualTo(Decision.NOT_LARGE);
        assertThat(check(5_000_000, null)).isEqualTo(Decision.PASSWORD_REQUIRED);
        assertThat(check(5_000_000, "123456")).isEqualTo(Decision.PASSED);
    }

    @Test
    @DisplayName("prod 프로필과 함께 켜면 기동을 멈춘다")
    void refusesToStartWithProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "fake-fds");

        assertThatThrownBy(() -> new FakeFdsService(environment, 5_000_000L))
                .isInstanceOf(IllegalStateException.class);
    }

    private Decision check(long amount, String wdrPwd) {
        return transactionTemplate.execute(status -> fdsService.checkWithdrawal(ACCOUNT, command(amount, wdrPwd))).decision();
    }

    private static WithdrawCommand command(long amount, String wdrPwd) {
        return new WithdrawCommand(amount, "테스트은행", "1234567890", UUID.randomUUID().toString(), wdrPwd,
                new RequestMeta("127.0.0.1", null, "-"));
    }
}
