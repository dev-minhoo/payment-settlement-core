package com.minhoo.paymentsettlementcore.outbox;

import com.minhoo.paymentsettlementcore.payment.PaymentRepository;
import com.minhoo.paymentsettlementcore.payment.PaymentResult;
import com.minhoo.paymentsettlementcore.payment.PaymentService;
import com.minhoo.paymentsettlementcore.settlement.ProcessedEventRepository;
import com.minhoo.paymentsettlementcore.settlement.SettlementEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * 결제 → 아웃박스 → Kafka → 정산 소비자까지 실제 브로커(docker-compose)로 확인한다.
 * 스케줄러는 테스트에서 꺼져 있으므로(build.gradle) 발행 시점을 relayPending()으로 직접 제어한다.
 */
@SpringBootTest
class OutboxRelayTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @MockitoSpyBean private OutboxEventSender outboxEventSender;

    @Autowired private OutboxRelay outboxRelay;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private SettlementEntryRepository settlementEntryRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        settlementEntryRepository.deleteAll();
        processedEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        var keys = redisTemplate.keys("idem:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @Test
    @DisplayName("발행에 성공하면 SENT가 되고, 소비자가 정산 원장에 1건 반영한다")
    void relayPublishesAndConsumerSettlesOnce() {
        Long paymentId = pay("PAY-RELAY-0001");

        int sent = outboxRelay.relayPending();

        assertThat(sent).isEqualTo(1);
        OutboxEvent event = onlyEvent();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(settlementEntryRepository.countByPaymentId(paymentId)).isEqualTo(1));
    }

    @Test
    @DisplayName("발행이 실패하면 PENDING으로 남고, 다음 실행에서 재발행되어 정산은 1건만 생긴다")
    void failedPublishIsRetriedAndSettledOnce() throws Exception {
        Long paymentId = pay("PAY-RELAY-0002");
        willThrow(new IllegalStateException("broker down"))
                .willCallRealMethod()
                .given(outboxEventSender).send(any());

        // 1차: 브로커 장애
        assertThat(outboxRelay.relayPending()).isZero();
        OutboxEvent failed = onlyEvent();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker down");

        // 2차: 복구 후 재발행
        assertThat(outboxRelay.relayPending()).isEqualTo(1);
        OutboxEvent sent = onlyEvent();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo(2);
        assertThat(sent.getLastError()).isNull();

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(settlementEntryRepository.countByPaymentId(paymentId)).isEqualTo(1));
    }

    @Test
    @DisplayName("같은 이벤트가 Kafka로 두 번 전달돼도(at-least-once) 정산은 1건이다")
    void duplicateDeliveryIsSettledOnce() throws Exception {
        Long paymentId = pay("PAY-RELAY-0003");
        outboxRelay.relayPending();
        OutboxEvent event = onlyEvent();

        // "발행 성공 후 SENT 저장 직전에 죽어서 다시 보낸" 상황을 재현: 같은 eventId로 한 번 더 발행
        outboxEventSender.send(event);

        // 같은 key(=같은 파티션)로 표지 이벤트를 보내고, 그것까지 처리됐으면 앞의 중복도 처리된 것
        OutboxEvent marker = OutboxEvent.pending("PAYMENT", event.getAggregateId(), "TEST_MARKER", "{}");
        outboxEventSender.send(marker);
        await().atMost(WAIT).until(() -> processedEventRepository.existsByEventId(marker.getEventId()));

        assertThat(settlementEntryRepository.countByPaymentId(paymentId)).isEqualTo(1);
        assertThat(processedEventRepository.countByEventId(event.getEventId())).isEqualTo(1);
    }

    private Long pay(String key) {
        PaymentResult result = paymentService.pay(key, new BigDecimal("10000"));
        assertThat(result.status()).isEqualTo(PaymentResult.Status.SUCCESS);
        return result.paymentId();
    }

    private OutboxEvent onlyEvent() {
        var events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        return events.get(0);
    }
}
