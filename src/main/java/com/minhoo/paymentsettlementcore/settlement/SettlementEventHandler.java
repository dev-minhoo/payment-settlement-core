package com.minhoo.paymentsettlementcore.settlement;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멱등 소비자 본체. "처리 기록 + 정산 반영"을 한 트랜잭션으로 묶는다.
 *
 * 1) 이미 처리한 eventId면 바로 건너뛴다 (빠른 경로)
 * 2) processed_event 저장 — 동시에 같은 이벤트가 들어오면 유니크 제약 위반으로 한쪽만 성공 (최종 방어선)
 * 3) 정산 원장 반영
 * 2와 3이 같은 트랜잭션이라 "기록만 되고 정산은 빠짐" 또는 그 반대가 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SettlementEventHandler {

    static final String EVENT_PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    private final ProcessedEventRepository processedEventRepository;
    private final SettlementEntryRepository settlementEntryRepository;
    private final JsonMapper jsonMapper;

    /**
     * @return 이번 호출에서 실제로 처리했으면 true, 이미 처리된 이벤트라 건너뛰었으면 false
     * @throws org.springframework.dao.DataIntegrityViolationException 동시에 같은 이벤트를 처리한 경우
     */
    @Transactional
    public boolean handle(String eventId, String eventType, String payload) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return false;
        }
        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, eventType));

        if (EVENT_PAYMENT_COMPLETED.equals(eventType)) {
            PaymentCompletedPayload p = jsonMapper.readValue(payload, PaymentCompletedPayload.class);
            settlementEntryRepository.save(new SettlementEntry(p.paymentId(), p.amount(), eventId));
        }
        return true;
    }
}
