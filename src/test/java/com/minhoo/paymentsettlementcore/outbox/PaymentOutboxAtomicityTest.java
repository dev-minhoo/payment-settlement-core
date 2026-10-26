package com.minhoo.paymentsettlementcore.outbox;

import com.minhoo.paymentsettlementcore.payment.PaymentRepository;
import com.minhoo.paymentsettlementcore.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 아웃박스 기록이 실패하면 결제 저장도 롤백되어야 한다.
 * "결제는 됐는데 이벤트는 없음"(이벤트 유실) 상태가 생기지 않는지 확인한다.
 */
@SpringBootTest
class PaymentOutboxAtomicityTest {

    @MockitoBean private OutboxAppender outboxAppender;

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        var keys = redisTemplate.keys("idem:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @Test
    @DisplayName("이벤트 기록이 실패하면 결제도 저장되지 않는다")
    void paymentIsRolledBackWhenOutboxFails() {
        given(outboxAppender.append(any(), any(), any(), any()))
                .willThrow(new IllegalStateException("아웃박스 저장 실패"));

        assertThatThrownBy(() -> paymentService.pay("PAY-OBX-ATOM-0001", new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paymentRepository.findByIdempotencyKey("PAY-OBX-ATOM-0001")).isEmpty();
        assertThat(paymentRepository.count()).isZero();
    }
}
