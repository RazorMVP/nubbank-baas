package com.nubbank.baas.engine.common;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter that masks PII patterns in log messages.
 * Wired in logback-spring.xml as %piimsg replacement for %msg.
 *
 * <p>Masks:
 * <ul>
 *   <li>13-19 digit sequences (PAN/card numbers): keep first 4, last 4, stars in between</li>
 *   <li>11 digit sequences (BVN, NIN): keep first 3, last 4, four stars in middle</li>
 *   <li>10 digit sequences (NUBAN accounts): keep first 3, last 3, four stars in middle</li>
 * </ul>
 *
 * <p>Order matters: PAN (longer) is replaced first so the shorter regex doesn't grab pieces
 * of it. Word-boundary anchors prevent masking the middle of longer numeric tokens.
 */
public class PiiMaskingConverter extends ClassicConverter {

    // Word-boundary anchored — avoids masking middles of longer numeric tokens
    private static final Pattern PAN = Pattern.compile("\\b(\\d{4})\\d{5,11}(\\d{4})\\b");
    private static final Pattern BVN_NIN = Pattern.compile("\\b(\\d{3})\\d{4}(\\d{4})\\b");
    private static final Pattern NUBAN = Pattern.compile("\\b(\\d{3})\\d{4}(\\d{3})\\b");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isEmpty()) return msg;
        // Order: longest pattern first
        msg = PAN.matcher(msg).replaceAll(m -> m.group(1) + "*".repeat(m.group().length() - 8) + m.group(2));
        msg = BVN_NIN.matcher(msg).replaceAll("$1****$2");
        msg = NUBAN.matcher(msg).replaceAll("$1****$2");
        return msg;
    }
}
