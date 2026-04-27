package com.nubbank.baas.engine.config;

import com.nubbank.baas.engine.partner.PartnerTier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@ConditionalOnBean(StringRedisTemplate.class)
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    @Value("${app.rate-limit.sandbox-rpm:30}")     private int sandboxRpm;
    @Value("${app.rate-limit.basic-rpm:100}")       private int basicRpm;
    @Value("${app.rate-limit.pro-rpm:500}")         private int proRpm;
    @Value("${app.rate-limit.enterprise-rpm:2000}") private int enterpriseRpm;

    // Lua script: atomic INCR + EXPIRE in a single round-trip
    private static final String LUA = """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then redis.call('EXPIRE', KEYS[1], 60) end
        return {current, redis.call('TTL', KEYS[1])}
        """;

    public record RateLimitResult(boolean allowed, long current, long limit, long resetInSeconds) {}

    public RateLimitResult check(String partnerId, String tier, String environment) {
        int limit = resolveLimit(tier, environment);
        String key = "rl:baas:" + partnerId;
        DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redis.execute(script, List.of(key),
            String.valueOf(limit));
        if (result == null) {
            // Redis unavailable — fail open (allow the request)
            return new RateLimitResult(true, 0, limit, 60);
        }
        long current = result.get(0);
        long ttl = result.get(1);
        return new RateLimitResult(current <= limit, current, limit, Math.max(ttl, 0));
    }

    private int resolveLimit(String tier, String environment) {
        if ("SANDBOX".equals(environment)) return sandboxRpm;
        return switch (PartnerTier.valueOf(tier)) {
            case BASIC -> basicRpm;
            case PRO -> proRpm;
            case ENTERPRISE -> enterpriseRpm;
            default -> sandboxRpm;
        };
    }
}
