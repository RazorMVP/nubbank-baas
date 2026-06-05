package com.nubbank.baas.card.card;

import com.nubbank.baas.card.card.dto.CardResponse;
import com.nubbank.baas.card.card.dto.IssueCardRequest;
import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.engine.EngineClient;
import com.nubbank.baas.card.engine.dto.AccountLookupRequest;
import com.nubbank.baas.card.engine.dto.AccountLookupResult;
import com.nubbank.baas.card.product.CardProduct;
import com.nubbank.baas.card.product.CardProductRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Card issuance + lifecycle, tenant-scoped.
 *
 * <p>Mirrors the engine {@code CustomerService} / {@code CardProductService} idiom:
 * every entry point calls {@link #requireContext()} first, then operates inside the
 * authenticated partner's schema (Hibernate routes automatically — NO partnerId
 * column or filter; the schema IS the isolation boundary).
 *
 * <p>SECURITY: the generated PAN never touches a log statement, a response DTO, or
 * any field other than {@code pan_encrypted} (AES-GCM) and its {@code pan_hash}
 * fingerprint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardService {

    /** Generated test-PAN length (16-digit, Luhn-valid). */
    private static final int PAN_LENGTH = 16;
    /** Default 8-digit test BIN when the product has no binStart configured. */
    private static final String DEFAULT_TEST_BIN = "50600000";
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");
    private static final SecureRandom RNG = new SecureRandom();

    /** Legal target states from each current state. Terminal states map to empty. */
    private static final Map<CardStatus, Set<CardStatus>> ALLOWED = Map.of(
        CardStatus.ISSUED,    Set.of(CardStatus.ACTIVE, CardStatus.CANCELLED),
        CardStatus.ACTIVE,    Set.of(CardStatus.BLOCKED, CardStatus.CANCELLED, CardStatus.EXPIRED),
        CardStatus.BLOCKED,   Set.of(CardStatus.ACTIVE, CardStatus.CANCELLED),
        CardStatus.CANCELLED, Set.of(),
        CardStatus.EXPIRED,   Set.of());

    private final CardRepository cardRepository;
    private final CardProductRepository cardProductRepository;
    private final PanHasher panHasher;
    private final EngineClient engineClient;

    @Transactional
    public CardResponse issue(IssueCardRequest req) {
        requireContext();

        CardProduct product = cardProductRepository.findById(req.productId())
            .orElseThrow(() -> BaasException.notFound("PRODUCT_NOT_FOUND",
                "Card product " + req.productId() + " not found"));

        // Validate the funding account exists in the engine BEFORE binding the card to it.
        // Off the hot path; prevents a card bound to a non-existent/foreign account (Stage 5).
        PartnerContext ctx = PartnerContext.get();
        AccountLookupResult lookup = engineClient.accountLookup(new AccountLookupRequest(
            ctx.partnerId(), ctx.schemaName(), req.linkedAccountId()));
        if (!lookup.exists()) {
            throw BaasException.badRequest("LINKED_ACCOUNT_NOT_FOUND",
                "linkedAccountId does not exist in the engine");
        }

        // Derive the 8-digit BIN from the product's binStart (normalized to 8 digits
        // by zero-padding on the right), or fall back to a default test BIN.
        String bin = resolveBin(product.getBinStart());
        String pan = generatePan(bin);

        Card card = Card.builder()
            .productId(product.getId())
            .customerRef(req.customerRef())
            .linkedAccountId(req.linkedAccountId())
            .panEncrypted(pan)                 // converter encrypts on persist
            .panHash(panHasher.hash(pan))      // deterministic lookup; set BEFORE save
            .panLast4(pan.substring(pan.length() - 4))
            .bin(bin)
            .expiryYm(LocalDate.now().plusYears(3).format(YYMM))
            .status(CardStatus.ISSUED)
            .virtual(req.virtual())
            .build();

        // The pan_hash UNIQUE constraint is the authoritative TOCTOU guard. A PAN
        // collision is astronomically unlikely, but the constraint is real — map any
        // duplicate-hash violation back to a clean 409. saveAndFlush forces the INSERT
        // (and the constraint) to fire HERE, inside the try, not at deferred commit.
        try {
            Card saved = cardRepository.saveAndFlush(card);
            // SAFE log: id + masked PAN only — never the raw PAN / blob / hash.
            log.info("Issued card {} (product {}, masked {})",
                saved.getId(), product.getId(), saved.maskedPan());
            return CardResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw BaasException.conflict("CARD_ISSUE_CONFLICT",
                "Card issuance conflict — please retry");
        }
    }

    @Transactional(readOnly = true)
    public List<CardResponse> list() {
        requireContext();
        return cardRepository.findAll().stream()
            .map(CardResponse::from)
            .toList();
    }

    @Transactional
    public CardResponse executeCommand(UUID id, String command) {
        requireContext();

        Card card = cardRepository.findById(id)
            .orElseThrow(() -> BaasException.notFound("CARD_NOT_FOUND",
                "Card " + id + " not found"));

        CardStatus target = switch (command == null ? "" : command.toLowerCase()) {
            case "activate" -> CardStatus.ACTIVE;
            case "block"    -> CardStatus.BLOCKED;
            case "unblock"  -> CardStatus.ACTIVE;
            case "cancel"   -> CardStatus.CANCELLED;
            default -> throw BaasException.badRequest("INVALID_COMMAND",
                "Unknown command '" + command + "' — expected activate|block|unblock|cancel");
        };

        transition(card, target);
        return CardResponse.from(cardRepository.save(card));
    }

    // ---- internals ----

    /** Guarded state transition. Illegal → 409 INVALID_TRANSITION. */
    private void transition(Card card, CardStatus target) {
        if (!ALLOWED.getOrDefault(card.getStatus(), Set.of()).contains(target)) {
            throw BaasException.conflict("INVALID_TRANSITION",
                "Cannot move card from " + card.getStatus() + " to " + target);
        }
        card.setStatus(target);
    }

    /**
     * Resolve the 8-digit BIN. The product's binStart may be 6 or 8 digits (or null);
     * keep up to 8 leading digits and right zero-pad to 8 — consistent with the BIN
     * module's normalization (Task 2). Falls back to {@link #DEFAULT_TEST_BIN}.
     */
    private String resolveBin(String binStart) {
        String digits = binStart == null ? "" : binStart.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return DEFAULT_TEST_BIN;
        }
        String head = digits.length() >= 8 ? digits.substring(0, 8) : digits;
        return String.format("%-8s", head).replace(' ', '0');
    }

    /**
     * Generate a Luhn-valid test PAN: the 8-digit BIN + random filler digits + a Luhn
     * check digit, to a total of {@link #PAN_LENGTH}. Test-only — production PANs come
     * from the personalization bureau.
     */
    private String generatePan(String bin) {
        StringBuilder sb = new StringBuilder(bin);
        // Fill up to PAN_LENGTH-1, leaving the last position for the check digit.
        while (sb.length() < PAN_LENGTH - 1) {
            sb.append(RNG.nextInt(10));
        }
        sb.append(luhnCheckDigit(sb.toString()));
        return sb.toString();
    }

    /** Compute the Luhn check digit for the given partial number. */
    private int luhnCheckDigit(String partial) {
        int sum = 0;
        boolean doubleDigit = true; // rightmost of partial is doubled (check digit is appended after)
        for (int i = partial.length() - 1; i >= 0; i--) {
            int d = partial.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return (10 - (sum % 10)) % 10;
    }

    private void requireContext() {
        if (PartnerContext.get() == null) {
            throw BaasException.unauthorized("MISSING_AUTH",
                "Authorization header required — use ApiKey or Bearer JWT");
        }
    }
}
