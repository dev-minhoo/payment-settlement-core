package com.minhoo.paymentsettlementcore.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 같은 계좌에서 동시에 출금할 때의 lost update 문제와 세 가지 방어.
 *
 * <pre>
 * 0. naive        : 읽기 → 계산 → 덮어쓰기. 동시 요청이 서로의 차감을 지운다.
 * 1. conditional  : UPDATE ... SET balance = balance - ? WHERE balance >= ?  (검사+차감이 한 문장)
 * 2. pessimistic  : SELECT ... FOR UPDATE 로 행을 잠그고 차감
 * 3. optimistic   : @Version 충돌 시 재시도 (재시도는 OptimisticWithdrawFacade 담당)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    /** 0. 문제 재현용. 실무에서 쓰면 안 되는 패턴. */
    @Transactional
    public void withdrawNaive(Long accountId, BigDecimal amount) {
        BigDecimal current = accountRepository.findBalance(accountId);
        if (current.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(accountId, current, amount);
        }
        simulateWork();   // 조회와 저장 사이에 다른 처리(외부 호출 등)가 끼는 상황
        accountRepository.overwriteBalance(accountId, current.subtract(amount));
    }

    /** 1. 조건부 UPDATE. DB가 행 잠금 안에서 검사와 차감을 같이 처리한다. */
    @Transactional
    public void withdrawConditional(Long accountId, BigDecimal amount) {
        int updated = accountRepository.withdrawIfEnough(accountId, amount);
        if (updated == 0) {
            throw new InsufficientBalanceException(accountId, amount);
        }
    }

    /** 2. 비관적 락. 커밋할 때까지 다른 트랜잭션은 같은 행에서 대기한다. */
    @Transactional
    public void withdrawPessimistic(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findByIdForUpdate(accountId).orElseThrow();
        account.withdraw(amount);
    }

    /**
     * 3. 낙관적 락 1회 시도. 다른 트랜잭션이 먼저 커밋했으면
     * 커밋 시점에 ObjectOptimisticLockingFailureException이 난다.
     * 트랜잭션 밖에서 재시도해야 하므로 재시도는 Facade가 맡는다.
     */
    @Transactional
    public void withdrawOptimisticOnce(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.withdraw(amount);
    }

    private void simulateWork() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
