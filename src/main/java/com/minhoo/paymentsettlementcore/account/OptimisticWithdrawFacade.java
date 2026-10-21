package com.minhoo.paymentsettlementcore.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 낙관적 락 재시도 담당.
 * 트랜잭션 안에서 재시도하면 같은 영속성 컨텍스트(오래된 version)로 다시 시도하게 되므로
 * 반드시 트랜잭션 바깥에서 새 트랜잭션으로 재시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimisticWithdrawFacade {

    static final int MAX_ATTEMPTS = 100;

    private final AccountService accountService;

    /** @return 성공까지 걸린 시도 횟수 (1이면 충돌 없이 한 번에 성공) */
    public int withdraw(Long accountId, BigDecimal amount) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                accountService.withdrawOptimisticOnce(accountId, amount);
                return attempt;
            } catch (OptimisticLockingFailureException e) {
                backoff();
            }
            // InsufficientBalanceException은 재시도해도 결과가 같으므로 그대로 던진다
        }
        throw new IllegalStateException("낙관적 락 재시도 한도 초과 - accountId=" + accountId);
    }

    private void backoff() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
