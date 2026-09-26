package com.minhoo.paymentsettlementcore.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idem:";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /**
     * 내가 소유한 키일 때만 삭제한다.
     * GET-비교-DEL 세 단계를 Redis 안에서 원자적으로 실행한다.
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public String tryAcquire(String idempotencyKey) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + idempotencyKey, token, DEFAULT_TTL);

        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    /** @return 실제로 해제했으면 true, 내 락이 아니었으면 false */
    public boolean release(String idempotencyKey, String token) {
        Long result = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(KEY_PREFIX + idempotencyKey),
                token
        );
        return Long.valueOf(1L).equals(result);
    }
}