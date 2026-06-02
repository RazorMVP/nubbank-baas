package com.nubbank.baas.fep.routing;

/**
 * Result of an authorization call to the Card service, and the request body used to invoke it.
 *
 * <p>Defined here (Task 5) because the {@link CardClient} interface needs it to compile.
 * Task 6's {@code AuthorizationHandler} will consume both this DTO and {@link CardClient}
 * without re-declaring them.
 *
 * <p>Fail-safe contract: {@link com.nubbank.baas.fep.client.HttpCardClient#authorize} returns
 * {@code decision="DECLINE", responseCode="96"} on any transport error so the Netty thread is
 * never interrupted by a checked exception.
 *
 * @param decision     {@code "APPROVE"} or {@code "DECLINE"}.
 * @param responseCode ISO 8583 DE39 value (e.g. {@code "00"}, {@code "51"}, {@code "96"}).
 * @param message      Human-readable explanation (logged at DEBUG; never contains PAN).
 */
public record AuthorizationDecision(String decision, String responseCode, String message) {

    /**
     * Request body sent to {@code POST /internal/v1/authorize}.
     *
     * <p><strong>Never log {@code pan}.</strong> Log only {@code partnerId} / {@code amountMinor}
     * / {@code currency} for diagnostics.
     *
     * @param partnerId   UUID string identifying the partner tenant.
     * @param schemaName  PostgreSQL schema name for this partner.
     * @param pan         Full PAN — NEVER logged.
     * @param amountMinor Transaction amount in minor currency units (e.g. cents).
     * @param currency    ISO 4217 numeric currency code (e.g. {@code "840"} for USD).
     */
    public record Request(
        String partnerId,
        String schemaName,
        String pan,
        long   amountMinor,
        String currency
    ) {}
}
