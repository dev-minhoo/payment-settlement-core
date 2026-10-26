package com.minhoo.paymentsettlementcore.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도메인 트랜잭션 안에서 아웃박스 이벤트를 기록한다.
 *
 * MANDATORY: 이미 열린 트랜잭션이 없으면 예외를 던진다.
 * 아웃박스는 "도메인 변경과 같은 트랜잭션"일 때만 의미가 있으므로,
 * 단독 호출로 이벤트만 따로 커밋되는 실수를 구조적으로 막는다.
 */
@Component
@RequiredArgsConstructor
public class OutboxAppender {

    private final OutboxEventRepository outboxEventRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(String aggregateType, String aggregateId, String eventType, String payload) {
        return outboxEventRepository.save(
                OutboxEvent.pending(aggregateType, aggregateId, eventType, payload)
        );
    }
}
