package com.minhoo.paymentsettlementcore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("결제를 저장하고 멱등성 키로 조회한다")
    void saveAndFindByIdempotencyKey() {
        // given
        Payment payment = new Payment("PAY-20260919-0001", new BigDecimal("10000"));

        // when
        paymentRepository.save(payment);
        Optional<Payment> found = paymentRepository.findByIdempotencyKey("PAY-20260919-0001");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo("10000");
        assertThat(found.get().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    @DisplayName("같은 멱등성 키로 두 번 저장하면 유니크 제약에 걸린다")
    void duplicateIdempotencyKeyIsRejected() {
        // given
        paymentRepository.saveAndFlush(new Payment("PAY-20260919-0002", new BigDecimal("10000")));

        // when & then
        assertThatThrownBy(() ->
                paymentRepository.saveAndFlush(new Payment("PAY-20260919-0002", new BigDecimal("10000")))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}