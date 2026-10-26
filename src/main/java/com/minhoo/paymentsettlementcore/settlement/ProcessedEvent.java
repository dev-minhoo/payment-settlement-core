package com.minhoo.paymentsettlementcore.settlement;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 소비자가 이미 처리한 이벤트 기록. event_id 유니크 제약이 중복 처리의 최종 방어선이다.
 * 실제 서비스라면 정산 서비스 쪽 DB에 있는 테이블이다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "processed_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_event_id", columnNames = "event_id")
)
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = LocalDateTime.now();
    }
}
