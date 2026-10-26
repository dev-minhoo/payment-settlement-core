package com.minhoo.paymentsettlementcore.outbox;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 아웃박스 이벤트 1건을 Kafka로 보내고, 브로커 ack를 받을 때까지 기다린다.
 *
 * - key = aggregateId: 같은 결제의 이벤트는 같은 파티션으로 가서 순서가 유지된다
 * - header eventId: 소비자가 중복 수신을 걸러내는 멱등성 키
 * - 동기 대기: ack를 확인해야 SENT로 바꿀 수 있다. 확인 없이 SENT로 바꾸면 유실이 생긴다
 */
@Component
@RequiredArgsConstructor
public class OutboxEventSender {

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${psc.kafka.topic.payment-events}")
    private String topic;

    @Value("${psc.outbox.relay.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    public void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
        record.headers().add(HEADER_EVENT_ID, event.getEventId().getBytes(UTF_8));
        record.headers().add(HEADER_EVENT_TYPE, event.getEventType().getBytes(UTF_8));

        kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
    }
}
