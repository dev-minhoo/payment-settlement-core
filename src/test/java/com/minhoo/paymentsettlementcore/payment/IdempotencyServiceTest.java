package com.minhoo.paymentsettlementcore.payment;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(redisTemplate.keys("idem:*"));
    }

    @Test
    @DisplayName("같은 키로 두 번 선점하면 두 번째는 실패한다")
    void secondAcquireFails() {
        String key = "PAY-TEST-0001";

        String first = idempotencyService.tryAcquire(key);
        String second = idempotencyService.tryAcquire(key);

        assertThat(first).isNotNull();
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("해제하면 다시 선점할 수 있다")
    void canReacquireAfterRelease() {
        String key = "PAY-TEST-0002";

        String token = idempotencyService.tryAcquire(key);
        idempotencyService.release(key, token);
        String again = idempotencyService.tryAcquire(key);

        assertThat(again).isNotNull();
    }

    @Test
    @DisplayName("동시에 100개가 들어와도 정확히 하나만 선점한다")
    void onlyOneWinsUnderConcurrency() throws InterruptedException {
        String key = "PAY-TEST-0003";
        int threadCount = 100;

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (idempotencyService.tryAcquire(key) != null) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 토큰으로는 해제되지 않는다")
    void cannotReleaseWithWrongToken() {
        String key = "PAY-TEST-0004";

        String ownerToken = idempotencyService.tryAcquire(key);
        boolean released = idempotencyService.release(key, "someone-elses-token");

        assertThat(released).isFalse();
        assertThat(idempotencyService.tryAcquire(key)).isNull();  // 여전히 잠겨 있음
        assertThat(idempotencyService.release(key, ownerToken)).isTrue();
    }
}

