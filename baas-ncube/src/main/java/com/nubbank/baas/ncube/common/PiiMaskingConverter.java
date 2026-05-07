package com.nubbank.baas.ncube.common;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter that masks PII patterns in log messages.
 * Wired in logback-spring.xml as %piimsg replacement for %msg.
 *
 * <p>Masks (in order, longest pattern first to prevent sub-match cannibalisation):
 * <ul>
 *   <li><b>PAN</b> (13–19 digit card numbers) — masked only when adjacent to a card-context
 *       word ({@code card}, {@code pan}, {@code primary}). Keeps first 4 + last 4.</li>
 *   <li><b>BVN / NIN</b> (11-digit Nigerian identifiers) — masked anywhere. 11-digit
 *       sequences rarely collide with timestamps (Unix-ms is 13, Unix-s is 10).</li>
 *   <li><b>NUBAN</b> (10-digit account numbers) — masked only when adjacent to an
 *       account-context word ({@code account}, {@code nuban}, {@code from}, {@code to},
 *       {@code debit}, {@code credit}). This avoids mangling Unix-second timestamps
 *       (JWT {@code iat}/{@code exp}/{@code nbf}, {@code Instant.getEpochSecond()}).</li>
 * </ul>
 *
 * <p><b>Scope limitation (Phase 1F-0):</b> only {@link ILoggingEvent#getFormattedMessage()}
 * is masked. MDC values ({@code %X{...}}), structured key-value arguments ({@code %kvp}),
 * and exception messages / stack traces ({@code %ex}, {@code %throwable}) are <b>NOT</b>
 * processed by this converter. Callers must avoid placing PII into MDC keys, exception
 * messages, or structured arguments. A future task will extend masking to throwables and
 * MDC; for now this converter is defence-in-depth on the message body only.
 *
 * <p><b>Performance:</b> three sequential {@code Matcher.replaceAll} calls per log line.
 * Patterns are compiled once at class load. {@code Matcher} is allocated per call (not
 * cached) — {@code Matcher} is not thread-safe and Logback may invoke {@code convert} from
 * any thread.
 */
public class PiiMaskingConverter extends ClassicConverter {

    // PAN — context-anchored: requires "card", "pan", or "primary" within 16 non-digit
    // chars before the number. Avoids false-positive masking of 13–19 digit Unix-ms
    // timestamps and Sleuth trace IDs.
    private static final Pattern PAN = Pattern.compile(
        "(?i)(?<=(?:card|pan|primary)[^\\d]{0,16})(\\d{4})\\d{5,11}(\\d{4})");

    // BVN / NIN — simple 11-digit match. 11-digit sequences are rare in non-PII contexts;
    // Unix epoch in seconds is 10 digits, in milliseconds is 13. Word-boundary anchored.
    private static final Pattern BVN_NIN = Pattern.compile("\\b(\\d{3})\\d{4}(\\d{4})\\b");

    // NUBAN — context-anchored: requires an account-context word within 16 non-digit
    // chars before the number. Avoids false-positive masking of 10-digit Unix-second
    // timestamps (JWT iat/exp/nbf, Instant.getEpochSecond()).
    private static final Pattern NUBAN = Pattern.compile(
        "(?i)(?<=(?:account|nuban|from|to|debit|credit)[^\\d]{0,16})(\\d{3})\\d{4}(\\d{3})");

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
