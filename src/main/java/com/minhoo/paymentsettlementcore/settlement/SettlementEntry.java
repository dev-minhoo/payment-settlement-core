package com.minhoo.paymentsettlementcore.settlement;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 완료 이벤트를 받아 쌓는 정산 원장 항목.
 * 같은 이벤트를 두 번 받아도 이 행이 두 번 생기면 안 된다(이중 정산).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_entry", indexes = @Index(name = "idx_settlement_payment_id", columnList = "payment_id"))
public class SettlementEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "source_event_id", nullable = false, length = 36)
    private String sourceEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public SettlementEntry(Long paymentId, BigDecimal amount, String sourceEventId) {
        this.paymentId = paymentId;
        this.amount = amount;
        this.sourceEventId = sourceEventId;
        this.createdAt = LocalDateTime.now();
    }
}
