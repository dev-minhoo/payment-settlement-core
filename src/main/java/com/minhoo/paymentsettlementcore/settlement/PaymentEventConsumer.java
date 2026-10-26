package com.minhoo.paymentsettlementcore.settlement;

import com.minhoo.paymentsettlementcore.outbox.OutboxEventSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * payment-events 토픽 소비자. Kafka에서 꺼낸 메시지를 SettlementEventHandler에 넘긴다.
 * 트랜잭션은 핸들러에만 있다. 여기서 중복(유니크 위반)을 잡아야 롤백된 트랜잭션 밖에서
 * 정상 처리로 마무리하고 오프셋을 넘길 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final SettlementEventHandler settlementEventHandler;

    @KafkaListener(topics = "${psc.kafka.topic.payment-events}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventId = header(record, OutboxEventSender.HEADER_EVENT_ID);
        String eventType = header(record, OutboxEventSender.HEADER_EVENT_TYPE);

        if (eventId == null || eventType == null) {
            log.warn("eventId/eventType 헤더 없는 메시지 건너뜀 - partition={}, offset={}",
                    record.partition(), record.offset());
            return;
        }
        consume(eventId, eventType, record.value());
    }

    /** @return 실제로 처리했으면 true, 중복이라 건너뛰었으면 false */
    public boolean consume(String eventId, String eventType, String payload) {
        try {
            boolean processed = settlementEventHandler.handle(eventId, eventType, payload);
            if (!processed) {
                log.info("중복 이벤트 건너뜀 - eventId={}", eventId);
            }
            return processed;
        } catch (DataIntegrityViolationException e) {
            log.info("동시 중복 이벤트 차단 - eventId={}", eventId);
            return false;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), UTF_8);
    }
}
