package com.nubbank.baas.engine.makerchecker;

/** Guardable command-type identifiers. Add one per guarded command (spec §6). */
public final class MakerCheckerCommandType {
    private MakerCheckerCommandType() {}
    public static final String ACCOUNT_OPEN = "ACCOUNT_OPEN";
}
