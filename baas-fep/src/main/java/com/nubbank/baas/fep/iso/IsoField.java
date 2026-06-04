package com.nubbank.baas.fep.iso;

/**
 * Compile-time Data Element (DE) constants for ISO 8583-1987.
 * <p>
 * Field numbering follows the ISO 8583-1987 standard:
 * DE0 is the MTI (treated as field 0 by jPOS GenericPackager).
 */
public final class IsoField {

    private IsoField() {}

    public static final int MTI                = 0;
    public static final int PAN                = 2;
    public static final int PROC_CODE          = 3;
    public static final int AMOUNT             = 4;
    public static final int TRANSMISSION_DTS   = 7;
    public static final int STAN               = 11;
    public static final int LOCAL_TIME         = 12;
    public static final int LOCAL_DATE         = 13;
    public static final int EXPIRY             = 14;
    public static final int POS_ENTRY          = 22;
    public static final int RRN                = 37;
    public static final int AUTH_CODE          = 38;
    public static final int RESPONSE_CODE      = 39;
    public static final int TERMINAL_ID        = 41;
    public static final int MERCHANT_ID        = 42;
    public static final int CURRENCY           = 49;
    public static final int NETWORK_MGMT_CODE  = 70;
    public static final int ORIGINAL_DATA      = 90;
}
