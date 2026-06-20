package com.nubbank.baas.engine.role;

/** Well-known built-in role names + scope values. */
public final class PartnerRoles {
    private PartnerRoles() {}
    public static final String ADMIN    = "PARTNER_ADMIN";
    public static final String MAKER    = "PARTNER_MAKER";
    public static final String APPROVER = "PARTNER_APPROVER";
    public static final String VIEWER   = "PARTNER_VIEWER";
    public static final String SCOPE_PARTNER  = "PARTNER";
    public static final String SCOPE_OPERATOR = "OPERATOR";
    public static final String SCOPE_SHARED   = "SHARED";
}
