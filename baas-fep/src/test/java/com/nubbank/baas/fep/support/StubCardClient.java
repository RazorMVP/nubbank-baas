package com.nubbank.baas.fep.support;

import com.nubbank.baas.fep.routing.AuthorizationDecision;
import com.nubbank.baas.fep.routing.CardClient;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.routing.ReversalDecision;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory test double for {@link CardClient}.
 *
 * <p>Designed to be shared across Task-5 ({@code BinResolverTest}) and Task-6
 * ({@code AuthorizationHandlerTest}).  Keep it simple: register BIN-to-route
 * mappings, configure the {@code authorize} response, and inspect captured state.
 *
 * <h2>Thread safety</h2>
 * <p>The lookup counter uses {@link AtomicInteger} so Caffeine's internal loading
 * thread does not race with the test assertion thread.
 */
public class StubCardClient implements CardClient {

    /** BIN → PartnerRoute map (keyed by normalized 8-char BIN). */
    private final Map<String, PartnerRoute> binMap = new HashMap<>();

    /** How many times {@link #lookupBin} has been called. Used to assert caching. */
    private final AtomicInteger callCount = new AtomicInteger(0);

    /** The authorization decision returned by {@link #authorize}. */
    private AuthorizationDecision authorizeResponse =
        new AuthorizationDecision("APPROVE", "00", "stub approve");

    /** The last request received by {@link #authorize} (null until first call). */
    private AuthorizationDecision.Request lastAuthorizeRequest;

    /** The reversal decision returned by {@link #reverse}. */
    private ReversalDecision reversalResponse = new ReversalDecision(true);

    /** The last request received by {@link #reverse} (null until first call). */
    private ReversalDecision.Request lastReversalRequest;

    // ──────────────────────────── CardClient ───────────────────────────────

    @Override
    public Optional<PartnerRoute> lookupBin(String bin) {
        callCount.incrementAndGet();
        return Optional.ofNullable(binMap.get(bin));
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationDecision.Request req) {
        // Capture the request so tests can assert what FEP sent.
        // NEVER log req.pan() — PAN must never appear in logs.
        this.lastAuthorizeRequest = req;
        return authorizeResponse;
    }

    @Override
    public ReversalDecision reverse(ReversalDecision.Request req) {
        this.lastReversalRequest = req;
        return reversalResponse;
    }

    // ──────────────────────────── Setup helpers ─────────────────────────────

    /**
     * Registers a normalized BIN → route mapping.
     *
     * @param normalizedBin 8-char zero-padded BIN (e.g. {@code "50600012"}).
     * @param route         the partner route to return for that BIN.
     */
    public StubCardClient register(String normalizedBin, PartnerRoute route) {
        binMap.put(normalizedBin, route);
        return this;
    }

    /**
     * Overrides the default APPROVE/00 response returned by {@link #authorize}.
     *
     * @param decision the decision to return (e.g. a DECLINE/51 for tests that exercise
     *                 insufficient-funds handling).
     */
    public StubCardClient withAuthorizeResponse(AuthorizationDecision decision) {
        this.authorizeResponse = decision;
        return this;
    }

    /**
     * Overrides the default {@code located=true} response returned by {@link #reverse}.
     */
    public StubCardClient withReversalResponse(ReversalDecision decision) {
        this.reversalResponse = decision;
        return this;
    }

    // ──────────────────────────── Inspection ────────────────────────────────

    /** Returns the number of times {@link #lookupBin} was called. */
    public int lookupCount() {
        return callCount.get();
    }

    /**
     * Returns the most recent request passed to {@link #authorize}, or {@code null}
     * if {@link #authorize} has not been called yet.
     */
    public AuthorizationDecision.Request lastAuthorizeRequest() {
        return lastAuthorizeRequest;
    }

    /**
     * Returns the most recent request passed to {@link #reverse}, or {@code null}
     * if {@link #reverse} has not been called yet.
     */
    public ReversalDecision.Request lastReversalRequest() {
        return lastReversalRequest;
    }
}
