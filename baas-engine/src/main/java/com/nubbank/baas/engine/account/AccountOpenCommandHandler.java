package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.OpenAccountRequest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandHandler;
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * First concrete maker-checker command handler — guards account-open. {@code validate} is the
 * submit-time courtesy check (customer exists); {@code execute} replays the real
 * {@link AccountService#open}, the same entry point the synchronous {@code POST /accounts} uses
 * (spec §6 cardinal rule — the deferred path must never be a stripped re-implementation).
 * Use this as the template when adding handlers for other guarded commands.
 */
@Component
@RequiredArgsConstructor
public class AccountOpenCommandHandler implements MakerCheckerCommandHandler {

    private final AccountService accountService;
    private final CustomerRepository customerRepo;

    @Override public String commandType() { return MakerCheckerCommandType.ACCOUNT_OPEN; }
    @Override public String requiredAuthorityToSubmit() { return "CREATE_ACCOUNT"; }
    @Override public String requiredAuthorityToApprove() { return "APPROVE_ACCOUNT"; }
    @Override public Class<?> payloadType() { return OpenAccountRequest.class; }

    @Override
    public void validate(Object payload) {
        // Courtesy (non-authoritative) subset of AccountService.open's validation — surfaces the
        // most common failure (unknown customer) at submit time; full validation runs in execute().
        OpenAccountRequest req = (OpenAccountRequest) payload;
        if (customerRepo.findById(req.customerId()).isEmpty())
            throw BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer " + req.customerId() + " not found");
    }

    @Override
    public UUID execute(Object payload) {
        // Replays the SAME service method the synchronous POST /accounts uses (spec §6 cardinal rule).
        return accountService.open((OpenAccountRequest) payload).id();
    }
}
