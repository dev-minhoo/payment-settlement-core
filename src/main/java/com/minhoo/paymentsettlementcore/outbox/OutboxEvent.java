package com.minhoo.paymentsettlementcore.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 트랜잭셔널 아웃박스.
 * 도메인 변경(결제 저장)과 같은 트랜잭션에서 이벤트를 DB에 먼저 기록하고,
 * 별도 발행기가 PENDING 이벤트를 Kafka로 내보낸다.
 * event_id는 소비자 쪽 멱등 처리 키로 쓰인다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "outbox_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_outbox_status_id", columnList = "status, id")
)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public static OutboxEvent pending(String aggregateType, String aggregateId, String eventType, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = UUID.randomUUID().toString();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.payload = payload;
        e.status = OutboxStatus.PENDING;
        e.attemptCount = 0;
        e.createdAt = LocalDateTime.now();
        return e;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.attemptCount++;
        this.sentAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void recordFailure(String error) {
        this.attemptCount++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}
