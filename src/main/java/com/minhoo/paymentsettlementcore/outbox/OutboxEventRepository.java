package com.minhoo.paymentsettlementcore.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // 발행기 폴링용: 오래된 PENDING부터 순서대로 (idx_outbox_status_id 사용)
    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(OutboxStatus status);

    long countByStatus(OutboxStatus status);
}
