package com.nubbank.baas.ncube.common;

/**
 * CBN-mandated vendor media types for open-banking endpoints.
 *
 * <p>The vendor type {@code application/vnd.cbn.openbanking.v1+json} versions the API
 * contract per CBN Open Banking Framework. Endpoints declare both {@code consumes} and
 * {@code produces} so requests with bare {@code application/json} content-type or accept
 * headers are rejected with 415 / 406 respectively.
 */
public final class CbnMediaTypes {

    public static final String CBN_OB_V1_JSON = "application/vnd.cbn.openbanking.v1+json";

    private CbnMediaTypes() {}
}
