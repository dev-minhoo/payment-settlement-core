package com.minhoo.paymentsettlementcore.payment;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentServiceTest {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        var keys = redisTemplate.keys("idem:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @Test
    @DisplayName("같은 요청을 순차로 두 번 보내면 두 번째는 DUPLICATED")
    void sequentialDuplicateReturnsDuplicated() {
        String key = "PAY-SVC-0001";

        var first  = paymentService.pay(key, new BigDecimal("10000"));
        var second = paymentService.pay(key, new BigDecimal("10000"));

        assertThat(first.status()).isEqualTo(PaymentResult.Status.SUCCESS);
        assertThat(second.status()).isEqualTo(PaymentResult.Status.DUPLICATED);
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 50개가 들어와도 결제는 정확히 한 건만 저장된다")
    void concurrentRequestsCreateOnlyOnePayment() throws InterruptedException {
        String key = "PAY-SVC-0002";
        int threadCount = 50;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (paymentService.pay(key, new BigDecimal("10000")).status()
                            == PaymentResult.Status.SUCCESS) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }
}