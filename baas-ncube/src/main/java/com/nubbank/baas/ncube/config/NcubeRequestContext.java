package com.nubbank.baas.ncube.config;

/**
 * Thread-local request context for baas-ncube.
 * Set by {@link InternalServiceAuthFilter} after HMAC validation.
 * Cleared in finally block. Read by {@link AuthEnforcementFilter} to gate /baas/v1/**.
 */
public final class NcubeRequestContext {
    private static final ThreadLocal<String> CALLER = new ThreadLocal<>();
    private NcubeRequestContext() {}
    public static void set(String caller) { CALLER.set(caller); }
    public static String get() { return CALLER.get(); }
    public static void clear() { CALLER.remove(); }
}
