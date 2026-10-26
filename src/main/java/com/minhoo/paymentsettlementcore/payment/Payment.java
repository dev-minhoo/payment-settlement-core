package com.minhoo.paymentsettlementcore.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = "idempotency_key")
)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Payment(String idempotencyKey, BigDecimal amount) {
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
    }

    /** 외부 이체가 성공한 결제를 완료 상태로 바꾼다. */
    public void complete() {
        if (this.status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException("REQUESTED 상태에서만 완료할 수 있습니다. 현재=" + this.status);
        }
        this.status = PaymentStatus.COMPLETED;
    }
}
