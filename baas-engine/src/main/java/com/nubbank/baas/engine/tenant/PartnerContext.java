package com.nubbank.baas.engine.tenant;

public record PartnerContext(
    String partnerId,
    String schemaName,
    String tier,
    String environment,
    String authMode
) {
    private static final ThreadLocal<PartnerContext> HOLDER = new ThreadLocal<>();

    public static void set(PartnerContext ctx) { HOLDER.set(ctx); }
    public static PartnerContext get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }

    public boolean isSandbox() { return "SANDBOX".equals(environment); }
}
