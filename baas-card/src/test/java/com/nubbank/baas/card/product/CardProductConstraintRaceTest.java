package com.nubbank.baas.card.product;

import com.nubbank.baas.card.card.CardType;
import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.product.dto.CreateCardProductRequest;
import com.nubbank.baas.card.tenant.PartnerContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Deterministic proof that the TOCTOU duplicate-name race maps to 409 DUPLICATE_PRODUCT
 * (not HTTP 500), by exercising the catch path in {@link CardProductService#create} directly.
 *
 * <p>Why a unit test (not an integration test): a genuine multi-threaded race is flaky, and
 * pre-seeding a row in the partner schema would also be seen by the {@code existsByName}
 * fast-path (so it would never reach the catch). Here we stub the repository so the fast-path
 * {@code existsByName} returns {@code false} (simulating the window where a concurrent request
 * has not yet committed) and {@code saveAndFlush} then throws the same
 * {@link DataIntegrityViolationException} the DB unique constraint raises when the concurrent
 * INSERT wins. This is exactly the interleaving the constraint guards against, made
 * deterministic. {@code saveAndFlush} (not {@code save}) is what makes the violation fire
 * synchronously inside the try block rather than escaping at transaction commit.
 *
 * <p>The complementary fast-path 409 (the common, non-concurrent case) is proven by
 * {@code CardProductTest#create_duplicateName_returns409}.
 */
class CardProductConstraintRaceTest {

    @Test
    void create_duplicateName_viaConstraint_returns409() {
        CardProductRepository repo = mock(CardProductRepository.class);
        CardProductService service = new CardProductService(repo);

        CreateCardProductRequest req = new CreateCardProductRequest(
            "Virtual Debit", CardType.DEBIT, "NGN", null, BigDecimal.valueOf(250000));

        // Fast-path miss: the concurrent duplicate has not yet committed when we check.
        when(repo.existsByName("Virtual Debit")).thenReturn(false);
        // The DB unique constraint on card_products.name fires when our INSERT is flushed.
        when(repo.saveAndFlush(any(CardProduct.class)))
            .thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"card_products_name_key\""));

        // PartnerContext is normally set by the tenant filter on the HTTP request; stub it
        // non-null so requireContext() passes and we reach the persistence guard under test.
        PartnerContext stubCtx = new PartnerContext(
            "partner-1", "partner_test", "SANDBOX", "SANDBOX", "JWT", "user-1");
        try (MockedStatic<PartnerContext> ctx = mockStatic(PartnerContext.class)) {
            ctx.when(PartnerContext::get).thenReturn(stubCtx);

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BaasException.class)
                .satisfies(ex -> {
                    BaasException be = (BaasException) ex;
                    assertThat(be.getCode()).isEqualTo("DUPLICATE_PRODUCT");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        }
    }
}
