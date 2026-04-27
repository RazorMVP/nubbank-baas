package com.nubbank.baas.ncube.account;

import com.nubbank.baas.ncube.account.dto.NubBankAccountDto;
import com.nubbank.baas.ncube.account.dto.NubBankTransactionDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubeAccountController.class)
@Import({com.nubbank.baas.ncube.common.GlobalExceptionHandler.class,
         com.nubbank.baas.ncube.config.SecurityConfig.class})
class NcubeAccountControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private NcubeAccountClient accountClient;

    @Test
    void getAccounts_returnsCbnFormat() throws Exception {
        when(accountClient.getAccounts(any())).thenReturn(List.of(
            new NubBankAccountDto("uuid-1","0581000042","Savings","ACTIVE",
                new BigDecimal("5000.00"), new BigDecimal("5000.00"), "NGN")));

        mockMvc.perform(get("/baas/v1/ncube/accounts").header("Authorization","Bearer jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Account[0].AccountId").value("0581000042"))
            .andExpect(jsonPath("$.Data.Account[0].Currency").value("NGN"))
            .andExpect(jsonPath("$.Data.Account[0].Account[0].SchemeName").value("NIBSS.AccountNumber"))
            .andExpect(jsonPath("$.Links.Self").exists())
            .andExpect(jsonPath("$.Meta.TotalPages").value(1));
    }

    @Test
    void getBalance_returnsCbnBalanceFormat() throws Exception {
        when(accountClient.getBalance(any(), any())).thenReturn(
            new NubBankAccountDto("uuid-1","0581000042","Savings","ACTIVE",
                new BigDecimal("5000.00"), new BigDecimal("5000.00"), "NGN"));

        mockMvc.perform(get("/baas/v1/ncube/accounts/0581000042/balances")
                .header("Authorization","Bearer jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Balance[0].AccountId").value("0581000042"))
            .andExpect(jsonPath("$.Data.Balance[0].CreditDebitIndicator").value("Credit"))
            .andExpect(jsonPath("$.Data.Balance[0].Amount.Amount").value("5000.00"))
            .andExpect(jsonPath("$.Data.Balance[0].Amount.Currency").value("NGN"));
    }

    @Test
    void getTransactions_returnsCbnTransactionFormat() throws Exception {
        when(accountClient.getTransactions(any(), any())).thenReturn(List.of(
            new NubBankTransactionDto("tx-001","CREDIT",new BigDecimal("1000.00"),
                new BigDecimal("6000.00"),"NGN","ref-001","2026-04-27T10:00:00Z")));

        mockMvc.perform(get("/baas/v1/ncube/accounts/0581000042/transactions")
                .header("Authorization","Bearer jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Transaction[0].AccountId").value("0581000042"))
            .andExpect(jsonPath("$.Data.Transaction[0].CreditDebitIndicator").value("Credit"))
            .andExpect(jsonPath("$.Data.Transaction[0].Status").value("Booked"));
    }
}
