package com.minhoo.paymentsettlementcore.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 폴링 발행기: PENDING 이벤트를 오래된 순서로 꺼내 Kafka로 보내고 SENT로 바꾼다.
 *
 * 전달 보장은 at-least-once다.
 * "발행 성공 → SENT 저장" 사이에 프로세스가 죽으면 다음 실행에서 같은 이벤트를 다시 보낸다.
 * 그래서 소비자는 eventId로 중복을 걸러야 한다(SettlementEventHandler).
 *
 * 한계: 발행기를 여러 대 띄우면 같은 PENDING을 동시에 집을 수 있다(중복 발행, 결과는 소비자 멱등으로 흡수).
 * 다중 인스턴스라면 SELECT ... FOR UPDATE SKIP LOCKED 또는 리더 선출로 한 대만 돌게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventSender outboxEventSender;

    /** @return 이번 실행에서 발행에 성공한 건수 */
    public int relayPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING);
        int sent = 0;

        for (OutboxEvent event : pending) {
            try {
                outboxEventSender.send(event);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                event.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
                outboxEventRepository.save(event);
                log.warn("아웃박스 발행 실패, 다음 실행에서 재시도 - eventId={}, attempt={}",
                        event.getEventId(), event.getAttemptCount());
                // 브로커 장애면 뒤 이벤트도 실패할 가능성이 크고, 순서도 지키기 위해 이번 배치를 멈춘다
                break;
            }

            event.markSent();
            outboxEventRepository.save(event);
            sent++;
        }
        return sent;
    }
}
