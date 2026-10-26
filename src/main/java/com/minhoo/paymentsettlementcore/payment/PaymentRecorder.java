package com.minhoo.paymentsettlementcore.payment;

import com.minhoo.paymentsettlementcore.outbox.OutboxAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 결제 완료 저장과 PAYMENT_COMPLETED 이벤트 기록을 한 트랜잭션으로 묶는다.
 *
 * PaymentService.pay() 전체에 트랜잭션을 걸지 않고 이 빈으로 분리한 이유
 * 1) 외부 이체 호출(지연)이 트랜잭션 안에 들어가 DB 커넥션을 오래 잡지 않게
 * 2) 유니크 제약 위반 시 이 트랜잭션만 롤백되고, 호출자는 트랜잭션 밖에서 기존 결제를 재조회할 수 있게
 * 3) 같은 클래스 내부 호출(self-invocation)은 프록시를 거치지 않아 @Transactional이 적용되지 않으므로 별도 빈으로 둔다
 */
@Component
@RequiredArgsConstructor
public class PaymentRecorder {

    static final String AGGREGATE_TYPE = "PAYMENT";
    static final String EVENT_PAYMENT_COMPLETED = "PAYMENT_COMPLETED";

    private final PaymentRepository paymentRepository;
    private final OutboxAppender outboxAppender;

    /**
     * 둘 다 커밋되거나 둘 다 롤백된다.
     * 중복 키면 saveAndFlush에서 DataIntegrityViolationException이 나고, 이벤트는 기록되지 않는다.
     */
    @Transactional
    public Payment recordCompleted(String idempotencyKey, BigDecimal amount) {
        Payment payment = new Payment(idempotencyKey, amount);
        payment.complete();
        paymentRepository.saveAndFlush(payment);

        outboxAppender.append(
                AGGREGATE_TYPE,
                String.valueOf(payment.getId()),
                EVENT_PAYMENT_COMPLETED,
                completedPayload(payment)
        );
        return payment;
    }

    private static String completedPayload(Payment p) {
        // amount는 JSON 숫자로 둔다(문자열이면 소비자 쪽 BigDecimal 변환이 매퍼 설정에 의존하게 된다)
        return "{\"paymentId\":%d,\"idempotencyKey\":\"%s\",\"amount\":%s}".formatted(
                p.getId(),
                escapeJson(p.getIdempotencyKey()),
                p.getAmount().toPlainString()
        );
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
