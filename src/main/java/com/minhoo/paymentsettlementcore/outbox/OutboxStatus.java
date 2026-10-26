package com.minhoo.paymentsettlementcore.outbox;

public enum OutboxStatus {
    PENDING,   // 저장됨, 아직 Kafka 발행 전 (또는 발행 실패로 재시도 대기)
    SENT       // Kafka 발행 확인(ack) 완료
}
