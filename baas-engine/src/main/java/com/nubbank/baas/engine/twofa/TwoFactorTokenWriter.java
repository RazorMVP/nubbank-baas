package com.nubbank.baas.engine.twofa;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * Persists OTP token attempt counter changes in a separate transaction so the
 * increment survives the rollback caused by throwing INVALID_OTP from the
 * caller. Without this the rollback would reset failedAttempts to 0 and the
 * brute-force lockout would never engage.
 *
 * Lives on a separate bean so the {@code @Transactional(REQUIRES_NEW)} is
 * actually intercepted — internal calls bypass the Spring proxy.
 */
@Component
@RequiredArgsConstructor
public class TwoFactorTokenWriter {

    private final TwoFactorTokenRepository repo;

    /**
     * Increment {@code failedAttempts}; lock the token if the threshold is reached.
     * Returns the updated token. Runs in its own transaction so it commits
     * independently of the caller's rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TwoFactorToken recordFailedAttempt(UUID tokenId, int maxAttempts) {
        TwoFactorToken token = repo.findById(tokenId).orElseThrow();
        int attempts = token.getFailedAttempts() + 1;
        token.setFailedAttempts(attempts);
        if (attempts >= maxAttempts) token.setLocked(true);
        return repo.save(token);
    }
}
