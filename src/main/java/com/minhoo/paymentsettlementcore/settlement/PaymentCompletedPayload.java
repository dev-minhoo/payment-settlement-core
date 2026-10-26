package com.minhoo.paymentsettlementcore.settlement;

import java.math.BigDecimal;

/** PAYMENT_COMPLETED 이벤트 본문. PaymentRecorder가 만드는 JSON과 필드가 같다. */
public record PaymentCompletedPayload(Long paymentId, String idempotencyKey, BigDecimal amount) {
}
