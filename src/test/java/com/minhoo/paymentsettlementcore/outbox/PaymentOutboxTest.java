package com.minhoo.paymentsettlementcore.outbox;

import com.minhoo.paymentsettlementcore.payment.PaymentRepository;
import com.minhoo.paymentsettlementcore.payment.PaymentResult;
import com.minhoo.paymentsettlementcore.payment.PaymentService;
import com.minhoo.paymentsettlementcore.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentOutboxTest {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxAppender outboxAppender;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        var keys = redisTemplate.keys("idem:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @Test
    @DisplayName("결제가 성공하면 결제(COMPLETED)와 PAYMENT_COMPLETED 이벤트(PENDING)가 함께 저장된다")
    void paymentAndOutboxEventAreSavedTogether() {
        var result = paymentService.pay("PAY-OBX-0001", new BigDecimal("10000"));

        assertThat(result.status()).isEqualTo(PaymentResult.Status.SUCCESS);
        assertThat(paymentRepository.findById(result.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(result.paymentId()));
        assertThat(event.getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getPayload()).contains("\"idempotencyKey\":\"PAY-OBX-0001\"");
    }

    @Test
    @DisplayName("같은 키로 다시 요청하면 이벤트가 추가로 생기지 않는다")
    void duplicateRequestDoesNotCreateAnotherEvent() {
        paymentService.pay("PAY-OBX-0002", new BigDecimal("10000"));
        var second = paymentService.pay("PAY-OBX-0002", new BigDecimal("10000"));

        assertThat(second.status()).isEqualTo(PaymentResult.Status.DUPLICATED);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 50개가 들어와도 결제 1건, 이벤트 1건만 남는다")
    void concurrentRequestsCreateOnlyOneEvent() throws InterruptedException {
        String key = "PAY-OBX-0003";
        int threadCount = 50;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    paymentService.pay(key, new BigDecimal("10000"));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("트랜잭션 밖에서 아웃박스만 단독으로 기록할 수 없다 (MANDATORY)")
    void appendOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> outboxAppender.append("PAYMENT", "1", "PAYMENT_COMPLETED", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }
}
