package com.nubbank.baas.engine.sandbox;

import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.account.dto.TransactionResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public TransactionResponse simulateDeposit(UUID accountId, BigDecimal amount) {
        requireSandbox();
        Account account = accountRepo.findByIdForUpdate(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));

        account.setBalance(account.getBalance().add(amount));
        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        accountRepo.save(account);

        Transaction tx = Transaction.builder()
            .account(account).transactionType(TransactionType.CREDIT)
            .amount(amount).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .description("SANDBOX: simulated deposit").build();
        tx = txRepo.save(tx);

        return new TransactionResponse(tx.getId(), account.getId(), tx.getTransactionType(),
            tx.getAmount(), tx.getRunningBalance(), tx.getCurrencyCode(), null, tx.getCreatedAt());
    }

    @Transactional
    public void reset() {
        requireSandbox();
        PartnerContext ctx = PartnerContext.get();

        // Always reset the sandbox_ schema, never the production partner_ schema
        String sandboxSchema = ctx.schemaName().startsWith("sandbox_")
            ? ctx.schemaName()
            : ctx.schemaName().replace("partner_", "sandbox_");

        log.info("Resetting sandbox schema: {}", sandboxSchema);

        // Truncate in FK-safe order
        String[] tables = {
            "audit_log", "transactions", "payments", "accounts", "customers",
            "exchange_rates", "loan_products", "deposit_products"
        };
        for (String table : tables) {
            jdbcTemplate.execute("TRUNCATE TABLE " + sandboxSchema + "." + table + " CASCADE");
        }
        log.info("Sandbox schema {} reset complete", sandboxSchema);
    }

    private void requireSandbox() {
        if (PartnerContext.get() == null) {
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        }
        if (!"SANDBOX".equals(PartnerContext.get().environment())) {
            throw BaasException.forbidden("SANDBOX_ONLY",
                "Sandbox simulation endpoints are only available in SANDBOX environment");
        }
    }
}
