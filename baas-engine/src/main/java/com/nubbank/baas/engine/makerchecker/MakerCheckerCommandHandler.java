package com.nubbank.baas.engine.makerchecker;

import java.util.UUID;

/**
 * One implementation per guarded command. The deferred (approve) path invokes the
 * SAME service entry point as the synchronous path — execute() must never be a
 * stripped re-implementation (spec §6 cardinal rule).
 */
public interface MakerCheckerCommandHandler {

    /** e.g. {@link MakerCheckerCommandType#ACCOUNT_OPEN}. */
    String commandType();

    /** Authority the maker must hold to submit, e.g. {@code CREATE_ACCOUNT}. */
    String requiredAuthorityToSubmit();

    /** Authority the checker must hold to approve, e.g. {@code APPROVE_ACCOUNT}. */
    String requiredAuthorityToApprove();

    /** Concrete request DTO type the payload deserializes to. */
    Class<?> payloadType();

    /** Submit-time courtesy validation (subset of real validation). Throws BaasException on failure. */
    void validate(Object payload);

    /** Replay through the real, fully-validating service method; return the created resource id. */
    UUID execute(Object payload);
}
