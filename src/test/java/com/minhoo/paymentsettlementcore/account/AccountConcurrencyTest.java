package com.minhoo.paymentsettlementcore.account;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AccountConcurrencyTest {

    private static final int REQUESTS = 100;
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal INITIAL = new BigDecimal("100000");   // 1,000원 x 100건이면 정확히 0이 되어야 한다

    @Autowired private AccountService accountService;
    @Autowired private OptimisticWithdrawFacade optimisticFacade;
    @Autowired private AccountRepository accountRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        accountId = accountRepository.save(new Account("tester", INITIAL)).getId();
    }

    @Test
    @DisplayName("[문제 재현] 읽고-계산하고-덮어쓰면 동시 출금 100건 중 일부가 사라진다 (lost update)")
    void naiveWithdrawLosesUpdates() throws InterruptedException {
        int success = runConcurrently(REQUESTS, id -> accountService.withdrawNaive(id, AMOUNT));

        BigDecimal balance = balance();
        BigDecimal expected = INITIAL.subtract(AMOUNT.multiply(BigDecimal.valueOf(success)));
        System.out.printf("[naive] 성공 응답 %d건, 기대 잔액 %s, 실제 잔액 %s → 사라진 출금 %s원%n",
                success, expected, balance, balance.subtract(expected));

        // 성공했다고 응답한 출금보다 잔액이 더 많이 남는다 = 출금 기록이 덮어써져 사라졌다
        assertThat(balance).isGreaterThan(expected);
    }

    @Test
    @DisplayName("[조건부 UPDATE] 동시 출금 100건 → 잔액 정확히 0")
    void conditionalUpdatePreventsLostUpdate() throws InterruptedException {
        int success = runConcurrently(REQUESTS, id -> accountService.withdrawConditional(id, AMOUNT));

        assertThat(success).isEqualTo(REQUESTS);
        assertThat(balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[비관적 락] 동시 출금 100건 → 잔액 정확히 0")
    void pessimisticLockPreventsLostUpdate() throws InterruptedException {
        int success = runConcurrently(REQUESTS, id -> accountService.withdrawPessimistic(id, AMOUNT));

        assertThat(success).isEqualTo(REQUESTS);
        assertThat(balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[낙관적 락 + 재시도] 동시 출금 100건 → 잔액 정확히 0, 재시도 횟수 기록")
    void optimisticLockWithRetryPreventsLostUpdate() throws InterruptedException {
        AtomicInteger totalAttempts = new AtomicInteger();

        int success = runConcurrently(REQUESTS,
                id -> totalAttempts.addAndGet(optimisticFacade.withdraw(id, AMOUNT)));

        System.out.printf("[optimistic] 100건 처리에 총 %d번 시도 (재시도 %d번)%n",
                totalAttempts.get(), totalAttempts.get() - REQUESTS);

        assertThat(success).isEqualTo(REQUESTS);
        assertThat(balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[잔액 부족] 잔액 10,000원에 1,000원 출금 30건이 동시에 와도 10건만 성공하고 음수가 되지 않는다")
    void neverGoesNegative() throws InterruptedException {
        Long smallAccount = accountRepository.save(new Account("small", new BigDecimal("10000"))).getId();

        int conditionalSuccess = runConcurrently(30, id -> accountService.withdrawConditional(smallAccount, AMOUNT));
        assertThat(conditionalSuccess).isEqualTo(10);
        assertThat(accountRepository.findBalance(smallAccount)).isEqualByComparingTo(BigDecimal.ZERO);

        Long smallAccount2 = accountRepository.save(new Account("small2", new BigDecimal("10000"))).getId();

        int pessimisticSuccess = runConcurrently(30, id -> accountService.withdrawPessimistic(smallAccount2, AMOUNT));
        assertThat(pessimisticSuccess).isEqualTo(10);
        assertThat(accountRepository.findBalance(smallAccount2)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 같은 작업을 동시에 n번 실행하고, 예외 없이 끝난 건수를 돌려준다. */
    private int runConcurrently(int n, Consumer<Long> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            executor.submit(() -> {
                try {
                    ready.await();             // 출발선에 모았다가 한꺼번에 출발
                    task.accept(accountId);
                    success.incrementAndGet();
                } catch (Exception ignored) {
                    // 잔액 부족 등 실패는 성공 건수에서 빠진다
                } finally {
                    done.countDown();
                }
            });
        }

        ready.countDown();
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        return success.get();
    }

    private BigDecimal balance() {
        return accountRepository.findBalance(accountId);
    }
}
