package com.nubbank.baas.fep.iso;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.stereotype.Component;

/**
 * Spring-managed factory for ISO 8583-1987 messages.
 * <p>
 * Loads the {@code iso8583-1987-fields.xml} jPOS {@link GenericPackager} config
 * from the classpath. The {@code genericpackager.dtd} ships inside the jPOS jar
 * and is resolved automatically by jPOS at parse time.
 * <p>
 * This class is safe to use as a plain Java object (no Spring context required)
 * because the constructor performs all initialisation eagerly.
 */
@Component
public class IsoMessageFactory {

    private final GenericPackager packager;

    public IsoMessageFactory() {
        try (var in = getClass().getResourceAsStream("/iso8583-1987-fields.xml")) {
            this.packager = new GenericPackager(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load ISO 8583 packager", e);
        }
    }

    /**
     * Create a new {@link ISOMsg} with the given MTI, ready for field population.
     *
     * @param mti 4-digit MTI string, e.g. {@code "0100"}
     * @return freshly created ISOMsg with packager attached
     */
    public ISOMsg create(String mti) {
        ISOMsg m = new ISOMsg();
        m.setPackager(packager);
        try {
            m.setMTI(mti);
        } catch (ISOException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    /**
     * Pack the message to its wire-format byte array.
     * <p>
     * This factory's packager is always (re)applied before packing, overriding any
     * packager the caller set, to prevent cross-factory packing bugs where an
     * {@link ISOMsg} created by a different factory or packager would otherwise be
     * packed with incompatible field definitions.
     *
     * @param m message to pack
     * @return packed bytes
     */
    public byte[] pack(ISOMsg m) {
        try {
            m.setPackager(packager);
            return m.pack();
        } catch (ISOException e) {
            throw new IllegalStateException("pack failed", e);
        }
    }

    /**
     * Unpack raw bytes into an {@link ISOMsg}.
     *
     * @param raw wire-format bytes
     * @return unpacked message
     * @throws IllegalArgumentException if the bytes cannot be parsed
     */
    public ISOMsg unpack(byte[] raw) {
        ISOMsg m = new ISOMsg();
        m.setPackager(packager);
        try {
            m.unpack(raw);
            return m;
        } catch (ISOException e) {
            throw new IllegalArgumentException("unpack failed", e);
        }
    }
}
