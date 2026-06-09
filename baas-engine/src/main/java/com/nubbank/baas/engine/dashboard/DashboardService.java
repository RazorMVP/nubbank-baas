package com.nubbank.baas.engine.dashboard;

import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.account.AccountStatus;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.customer.KycStatus;
import com.nubbank.baas.engine.loan.LoanRepository;
import com.nubbank.baas.engine.loan.LoanStatus;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Computes the dashboard summary tiles. Every repository call inherits the request's
 * {@link PartnerContext}, so all counts/sums are automatically scoped to the partner schema.
 *
 * <p>Deliberately NOT wrapped in a single transaction: each tile is an independent read with no
 * cross-tile consistency requirement, and the best-effort card-service call must not hold a DB
 * connection while it waits on the network.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Loans that represent live, money-out exposure. */
    private static final List<LoanStatus> ACTIVE_LOAN_STATUSES =
        List.of(LoanStatus.DISBURSED, LoanStatus.ACTIVE, LoanStatus.IN_ARREARS);

    private final CustomerRepository customerRepo;
    private final AccountRepository accountRepo;
    private final LoanRepository loanRepo;
    private final CardStatsClient cardStatsClient;

    public DashboardSummaryResponse summary() {
        long totalCustomers = customerRepo.count();
        long kycPending = customerRepo.countByKycStatus(KycStatus.PENDING_KYC);
        long totalAccounts = accountRepo.count();
        long activeAccounts = accountRepo.countByStatus(AccountStatus.ACTIVE);
        BigDecimal deposits = accountRepo.sumBalanceByStatus(AccountStatus.ACTIVE);
        long totalLoans = loanRepo.count();
        long activeLoans = loanRepo.countByStatusIn(ACTIVE_LOAN_STATUSES);

        PartnerContext ctx = PartnerContext.get();
        Long cardsIssued = ctx == null ? null
            : cardStatsClient.cardsIssued(ctx.partnerId(), ctx.schemaName());

        return new DashboardSummaryResponse(
            totalCustomers, kycPending, totalAccounts, activeAccounts,
            deposits == null ? BigDecimal.ZERO : deposits,
            totalLoans, activeLoans, cardsIssued);
    }
}
