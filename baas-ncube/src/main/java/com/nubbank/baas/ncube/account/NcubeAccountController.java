package com.nubbank.baas.ncube.account;

import com.nubbank.baas.ncube.account.dto.*;
import com.nubbank.baas.ncube.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(
    value = "/baas/v1/ncube/accounts",
    produces = CbnMediaTypes.CBN_OB_V1_JSON)
@RequiredArgsConstructor
public class NcubeAccountController {

    private final NcubeAccountClient accountClient;
    private static final String BASE = "https://api.nubbank.com/baas/v1/ncube/accounts";

    @GetMapping
    public CbnApiResponse<Map<String, Object>> getAccounts(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        List<CbnAccountItem> accounts = accountClient.getAccounts(auth).stream()
            .map(this::toCbn).toList();
        return new CbnApiResponse<>(Map.of("Account", accounts), new CbnLinks(BASE), new CbnMeta(1));
    }

    @GetMapping("/{accountNumber}/balances")
    public CbnApiResponse<Map<String, Object>> getBalance(
            @PathVariable String accountNumber,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        NubBankAccountDto dto = accountClient.getBalance(auth, accountNumber);
        CbnBalanceItem balance = new CbnBalanceItem(
            dto.accountNumber(), "Credit", "ClosingAvailable",
            Instant.now().toString(),
            new CbnAmount(dto.balance().toPlainString(), dto.currencyCode()));
        return new CbnApiResponse<>(Map.of("Balance", List.of(balance)),
            new CbnLinks(BASE + "/" + accountNumber + "/balances"), new CbnMeta(1));
    }

    @GetMapping("/{accountNumber}/transactions")
    public CbnApiResponse<Map<String, Object>> getTransactions(
            @PathVariable String accountNumber,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        List<CbnTransactionItem> txns = accountClient.getTransactions(auth, accountNumber).stream()
            .map(t -> new CbnTransactionItem(
                accountNumber, t.id(),
                "CREDIT".equals(t.transactionType()) ? "Credit" : "Debit",
                "Booked", t.createdAt(),
                new CbnAmount(t.amount().toPlainString(), t.currencyCode()),
                t.reference()))
            .toList();
        return new CbnApiResponse<>(Map.of("Transaction", txns),
            new CbnLinks(BASE + "/" + accountNumber + "/transactions"), new CbnMeta(1));
    }

    private CbnAccountItem toCbn(NubBankAccountDto dto) {
        return new CbnAccountItem(
            dto.accountNumber(), dto.currencyCode(), "Personal",
            dto.accountTypeLabel() != null ? dto.accountTypeLabel() : "CurrentAccount",
            dto.accountTypeLabel(),
            List.of(new CbnAccountScheme("NIBSS.AccountNumber", dto.accountNumber(), "", "NubBank")));
    }
}
