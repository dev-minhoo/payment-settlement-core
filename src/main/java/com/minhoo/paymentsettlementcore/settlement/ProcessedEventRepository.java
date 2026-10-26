package com.minhoo.paymentsettlementcore.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);

    long countByEventId(String eventId);
}
