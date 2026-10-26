package com.minhoo.paymentsettlementcore.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소비자 멱등성을 Kafka 없이 직접 호출로 확인한다.
 * paymentId는 실제 결제와 겹치지 않게 큰 값을 쓴다.
 */
@SpringBootTest
class PaymentEventConsumerTest {

    @Autowired private PaymentEventConsumer consumer;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private SettlementEntryRepository settlementEntryRepository;

    @BeforeEach
    void setUp() {
        settlementEntryRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받으면 두 번째는 건너뛰고 정산은 1건이다")
    void sameEventTwiceIsProcessedOnce() {
        String eventId = UUID.randomUUID().toString();
        String payload = payload(900_001L);

        boolean first = consumer.consume(eventId, "PAYMENT_COMPLETED", payload);
        boolean second = consumer.consume(eventId, "PAYMENT_COMPLETED", payload);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(settlementEntryRepository.countByPaymentId(900_001L)).isEqualTo(1);
        assertThat(processedEventRepository.countByEventId(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트가 동시에 20번 들어와도 정확히 한 번만 처리된다")
    void sameEventConcurrentlyIsProcessedOnce() throws InterruptedException {
        String eventId = UUID.randomUUID().toString();
        String payload = payload(900_002L);
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger processedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    if (consumer.consume(eventId, "PAYMENT_COMPLETED", payload)) {
                        processedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();   // 동시에 출발
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(processedCount.get()).isEqualTo(1);
        assertThat(settlementEntryRepository.countByPaymentId(900_002L)).isEqualTo(1);
        assertThat(processedEventRepository.countByEventId(eventId)).isEqualTo(1);
    }

    private static String payload(long paymentId) {
        return "{\"paymentId\":%d,\"idempotencyKey\":\"TEST-%d\",\"amount\":10000.00}".formatted(paymentId, paymentId);
    }
}
