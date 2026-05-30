package com.nubbank.baas.engine.partner;

public enum PartnerStatus {
    SANDBOX, PENDING_REVIEW, BASIC, PRO, ENTERPRISE, SUSPENDED;

    /** A partner whose operators may authenticate: provisioned and not suspended/under-review. */
    public boolean isActiveForAuth() {
        return this == SANDBOX || this == BASIC || this == PRO || this == ENTERPRISE;
    }
}
