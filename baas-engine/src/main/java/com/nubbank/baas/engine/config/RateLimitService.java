package com.nubbank.baas.engine.config;

import com.nubbank.baas.engine.partner.PartnerTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
public class RateLimitService {

    // Optional injection: StringRedisTemplate is auto-configured but this bean is registered
    // before auto-config runs. Using @Autowired(required=false) ensures we never fail to start.
    @Autowired(required = false)
    private StringRedisTemplate redis;

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

    public boolean isAvailable() {
        return redis != null;
    }

    public RateLimitResult check(String partnerId, String tier, String environment) {
        int limit = resolveLimit(tier, environment);
        if (redis == null) {
            // Redis not wired — fail open
            return new RateLimitResult(true, 0, limit, 60);
        }
        String key = "rl:baas:" + partnerId;
        @SuppressWarnings("unchecked")
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>(LUA, (Class<List<Long>>) (Class<?>) List.class);
        List<Long> result = redis.execute(script, List.of(key),
            String.valueOf(limit));
        if (result == null) {
            // Redis returned null — fail open (allow the request)
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
