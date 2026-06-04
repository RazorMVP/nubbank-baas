package com.nubbank.baas.card.bin;

import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BIN range registration + lookup.
 *
 * SECURITY INVARIANT: on registration, {@code partnerId} and {@code schemaName}
 * are taken ONLY from the authenticated {@link PartnerContext} — never from the
 * request body. A partner must not be able to register a BIN that resolves to
 * another tenant's schema.
 *
 * The {@link #lookup} path runs with NO PartnerContext (the internal HMAC caller
 * is tenant-less). That is correct: {@link CardBinRange} is pinned to the public
 * schema, so the row is reachable regardless of tenant context.
 */
@Service
@RequiredArgsConstructor
public class BinService {

    private final CardBinRangeRepository repo;

    /**
     * Resolve which partner/schema owns the given PAN/BIN. The input is normalized
     * to the FROZEN 8-char form before matching.
     */
    @Transactional(readOnly = true)
    public Optional<CardBinRange> lookup(String bin) {
        String norm = normalize(bin);
        return repo.findMatching(norm).stream().findFirst();
    }

    /**
     * Register a BIN range for the authenticated partner. partnerId/schemaName are
     * captured from {@link PartnerContext}; the body supplies only the range + scheme.
     */
    @Transactional
    public CardBinRange register(String binStart, String binEnd, String scheme) {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) {
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        }
        String start = normalize(binStart);
        String end = normalizeRangeEnd(binEnd);
        if (start.compareTo(end) > 0) {
            throw BaasException.badRequest("INVALID_BIN_RANGE", "bin_start must be <= bin_end");
        }
        return repo.save(CardBinRange.builder()
            .binStart(start)
            .binEnd(end)
            .partnerId(UUID.fromString(ctx.partnerId()))
            .schemaName(ctx.schemaName())
            .scheme(scheme)
            .build());
    }

    /** List BIN ranges owned by the authenticated partner. */
    @Transactional(readOnly = true)
    public List<CardBinRange> listForCurrentPartner() {
        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) {
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        }
        return repo.findByPartnerIdOrderByBinStartAsc(UUID.fromString(ctx.partnerId()));
    }

    /**
     * FROZEN cross-track contract (Card + FEP must be byte-identical):
     * strip non-digits, keep the first 8 digits (or fewer if shorter), then
     * LEFT-ALIGN and ZERO-PAD on the right to 8 chars.
     *
     * e.g. normalize("506000")="50600000", normalize("506099")="50609900",
     *      normalize("50600012")="50600012".
     *
     * {@code String.format("%-8s", head)} left-justifies in an 8-wide field
     * (padding with spaces on the right); {@code .replace(' ', '0')} turns that
     * right-padding into zeros — i.e. right zero-pad to 8.
     */
    static String normalize(String bin) {
        String digits = bin == null ? "" : bin.replaceAll("\\D", "");
        String head = digits.length() >= 8 ? digits.substring(0, 8) : digits;
        return String.format("%-8s", head).replace(' ', '0');
    }

    /**
     * FROZEN lookup {@link #normalize} pads the digit head with {@code '0'}; a range
     * END must instead pad with {@code '9'} so a short BIN covers its FULL sub-range.
     * e.g. normalizeRangeEnd("506000") = "50600099" — so a single 6-digit BIN
     * registered as start==end covers every PAN beginning 506000 (first-8 in
     * [50600000, 50600099]).
     */
    static String normalizeRangeEnd(String bin) {
        String digits = bin == null ? "" : bin.replaceAll("\\D", "");
        String head = digits.length() >= 8 ? digits.substring(0, 8) : digits;
        return String.format("%-8s", head).replace(' ', '9');
    }
}
