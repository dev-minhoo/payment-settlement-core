package com.minhoo.paymentsettlementcore.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;

    public PaymentResult pay(String idempotencyKey, BigDecimal amount) {

        // 0. 이미 처리 완료된 요청이면 기존 결과를 그대로 돌려준다 (빠른 경로)
        var completed = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (completed.isPresent()) {
            return PaymentResult.duplicated(completed.get());
        }

        // 1. 처리 구간 선점 — 실패하면 다른 요청이 이미 처리 중
        String token = idempotencyService.tryAcquire(idempotencyKey);
        if (token == null) {
            return PaymentResult.inProgress();
        }

        try {
            // 2. 외부 이체 호출 (여기서는 흉내만)
            callExternalTransfer(idempotencyKey, amount);

            // 3. 저장 — 최종 방어선은 DB 유니크 제약
            Payment saved = paymentRepository.saveAndFlush(new Payment(idempotencyKey, amount));
            return PaymentResult.success(saved);

        } catch (DataIntegrityViolationException e) {
            // 락이 TTL로 먼저 풀려 다른 요청이 이미 저장한 경우
            log.warn("중복 저장 차단 - key={}", idempotencyKey);
            return PaymentResult.duplicated(
                    paymentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow()
            );

        } finally {
            idempotencyService.release(idempotencyKey, token);
        }
    }

    private void callExternalTransfer(String key, BigDecimal amount) {
        log.info("외부 이체 호출 - key={}, amount={}", key, amount);
        try {
            Thread.sleep(100);   // 외부 API 지연 흉내
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}