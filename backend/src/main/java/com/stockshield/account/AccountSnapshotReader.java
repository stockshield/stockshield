package com.stockshield.account;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.stockshield.common.error.ApiException;
import com.stockshield.common.error.ErrorCode;
import com.stockshield.ledger.Account;
import com.stockshield.ledger.AccountRepository;
import com.stockshield.ledger.Holding;
import com.stockshield.ledger.HoldingRepository;

/**
 * 계좌와 보유 종목을 같은 시점으로 읽음. 따로 읽으면 그 사이 체결된 주문 때문에
 * 예수금은 줄었는데 보유 수량은 늘지 않은 총 자산이 화면에 보일 수 있음. 락은 잡지 않음.
 */
@Component
public class AccountSnapshotReader {

    public record Snapshot(Account account, List<Holding> holdings) {
    }

    private final AccountRepository accounts;
    private final HoldingRepository holdings;

    public AccountSnapshotReader(AccountRepository accounts, HoldingRepository holdings) {
        this.accounts = accounts;
        this.holdings = holdings;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(long mbrNo) {
        Account account = accounts.findByMbrNo(mbrNo)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return new Snapshot(account,
                holdings.findByAcntNoAndHldQtyGreaterThanOrderByTckCd(account.getAcntNo(), 0L));
    }
}
