package com.minhoo.paymentsettlementcore.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 발행기를 주기적으로 실행한다.
 * fixedDelay: 직전 실행이 끝난 뒤부터 시간을 잰다. 발행이 느려져도 실행이 겹치지 않는다.
 * 테스트에서는 psc.outbox.relay.enabled=false로 끄고 OutboxRelay를 직접 호출한다.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "psc.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay outboxRelay;

    @Scheduled(fixedDelayString = "${psc.outbox.relay.fixed-delay-ms:1000}")
    public void run() {
        outboxRelay.relayPending();
    }
}
