package com.nubbank.baas.fep.routing;

import java.util.Optional;

/**
 * Internal contract for reaching the baas-card service.
 *
 * <p>Implementations must be fail-closed on {@link #lookupBin} (return
 * {@link Optional#empty()} on any error) and fail-safe on {@link #authorize}
 * (return a RC-96 DECLINE rather than propagating exceptions into Netty threads).
 *
 * <p>Production implementation: {@link com.nubbank.baas.fep.client.HttpCardClient}.
 * Test implementation: {@link com.nubbank.baas.fep.support.StubCardClient}.
 */
public interface CardClient {

    /**
     * Looks up a normalized 8-char BIN (as produced by {@link BinResolver#bin(String)}) and
     * returns the partner routing context, or empty if the BIN is unknown.
     *
     * @param bin 8-character zero-padded BIN string (digits only, no PAN).
     * @return routing context, or {@link Optional#empty()} when the BIN is not registered.
     */
    Optional<PartnerRoute> lookupBin(String bin);

    /**
     * Requests an authorization decision from the Card service.
     *
     * @param req authorization request (contains PAN — NEVER log it).
     * @return decision from Card, or a fail-safe RC-96 DECLINE on transport failure.
     */
    AuthorizationDecision authorize(AuthorizationDecision.Request req);

    /**
     * Asks Card to locate and reverse the original authorization (F6). Fail-closed:
     * implementations return {@code located=false} on any transport error so the Netty
     * thread is never interrupted.
     */
    ReversalDecision reverse(ReversalDecision.Request req);
}
